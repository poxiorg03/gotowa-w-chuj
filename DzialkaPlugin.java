package pl.easymine.dzialka;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.io.*;
import java.util.*;

public final class DzialkaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
  private static final String PRE="§8[§aDziałka§8] §7";
  private static final String SIZE_MENU="§2§lRozmiar działki", SIZE_PAYMENT="§2§lPłatność za rozmiar";
  private final Map<UUID,UUID> pendingSize=new HashMap<>();
  private static final String CHALLENGES="§2§lWyzwania tygodniowe";
  private static final String UPGRADES="§2§lUlepszenia mechanizmów", PAYMENT="§2§lWybór płatności";
  private final Map<UUID,String> pendingUpgrade=new HashMap<>();
  private final Map<UUID,SaleOffer> saleOffers=new HashMap<>();
  private final Map<UUID,List<UUID>> offerMenuRows=new HashMap<>();
  private static final String OFFERS_MENU="§2§lOferty działek";
  private record SaleOffer(UUID plotId,UUID seller,UUID buyer,double price,long expires) {}
  private final Map<UUID,UUID> pendingUpgradePlot=new HashMap<>();
  private static final String[] MACHINES={"hoppery","tloki","redstone"};
  private static final String MAIN="§2§lDziałka", MEMBERS="§2§lCzłonkowie działki", SETTINGS="§2§lUstawienia działki", CONFIRM="§4§lUsunąć działkę?", SHOP="§2§lKup działkę";
  private final Map<UUID,Plot> plots=new LinkedHashMap<>();
  private final Map<UUID,UUID> selectedPlots=new HashMap<>();
  // Cache liczb mechanizmów; przy pierwszym sprawdzeniu wymagany jest skan terenu.
  private final Map<UUID,Map<String,Integer>> machineCounts=new HashMap<>();
  private final Map<UUID,UUID> invites=new HashMap<>();
  private final Map<UUID,Long> inviteExpiry=new HashMap<>();
  private final Map<UUID,BukkitTask> borders=new HashMap<>();
  private final Map<UUID,UUID> previous=new HashMap<>();
  private final Map<UUID,UUID> shownNight=new HashMap<>();
  private File file;
  private boolean dataLoadFailed=false;
  private File messagesFile;
  private YamlConfiguration messages;
  private final Set<UUID> pendingRemoval=new HashSet<>();
  private Economy eco;
  private NamespacedKey itemKey,displayKey,legacyItemKey,legacyDisplayKey,hologramKey;
  private static final String[] FLAGS={"pvp","potwory","zwierzeta","wejscie","skrzynie","drzwi","eksplozje","ogien","latanie","noc"};
  private static final String[] LABELS={"PvP","Potwory","Zwierzęta","Wejście obcych","Skrzynie obcych","Drzwi obcych","Eksplozje","Rozprzestrzenianie ognia","Latanie","Ciągła noc"};
  private static final Material[] ICONS={Material.IRON_SWORD,Material.ZOMBIE_HEAD,Material.EGG,Material.OAK_DOOR,Material.CHEST,Material.IRON_DOOR,Material.TNT,Material.FLINT_AND_STEEL,Material.FEATHER,Material.CLOCK};
  private static final class Plot {
    UUID id=UUID.randomUUID(); UUID groupId=null; UUID owner; String world; int x,y,z; int size=32; Set<UUID> members=new HashSet<>(), deputies=new HashSet<>(); Map<String,Boolean> flags=new HashMap<>(); double bank=0; String title="", description=""; boolean publicWarp=false; Set<UUID> likes=new HashSet<>(); Map<UUID,Set<String>> memberPermissions=new HashMap<>(); Map<String,Integer> machineLevels=new HashMap<>(); String challengeWeek=""; int challengeBlocks=0; boolean challengeClaimed=false;
    Plot(UUID o,String w,int x,int y,int z){owner=o;world=w;this.x=x;this.y=y;this.z=z;}
    // Rozmiar jest długością boku; także 250x250 jest dozwolone.
    int minX(){return x-size/2;}
    int minZ(){return z-size/2;}
    int maxX(){return minX()+size-1;}
    int maxZ(){return minZ()+size-1;}
    boolean contains(Location l){return l.getWorld()!=null&&l.getWorld().getName().equals(world)&&l.getBlockX()>=minX()&&l.getBlockX()<=maxX()&&l.getBlockZ()>=minZ()&&l.getBlockZ()<=maxZ();}
    boolean can(UUID id){return owner.equals(id)||members.contains(id)||deputies.contains(id);}
    boolean permit(UUID id,String action){return owner.equals(id)||deputies.contains(id)||(members.contains(id)&&memberPermissions.getOrDefault(id,Collections.emptySet()).contains(action));}
    boolean manager(UUID id){return owner.equals(id)||deputies.contains(id);}
    boolean flag(String s){return flags.getOrDefault(s,false);}
  }
  @Override public void onEnable(){
    if(!migrateLegacyData()){getLogger().severe("Migracja danych nie powiodla sie. Plugin wylaczony, aby nie nadpisac dzialek.");getServer().getPluginManager().disablePlugin(this);return;} saveDefaultConfig(); loadMessages(); itemKey=new NamespacedKey(this,"blok_dzialki"); displayKey=new NamespacedKey(this,"model_dzialki"); hologramKey=new NamespacedKey(this,"hologram_dzialki"); legacyItemKey=new NamespacedKey("easymineplots","blok_dzialki");legacyDisplayKey=new NamespacedKey("easymineplots","model_dzialki"); file=new File(getDataFolder(),"dzialki.yml"); backup(); load();
    if(getServer().getPluginManager().getPlugin("Vault")!=null){RegisteredServiceProvider<Economy> r=getServer().getServicesManager().getRegistration(Economy.class);if(r!=null)eco=r.getProvider();}
    Objects.requireNonNull(getCommand("dzialka")).setExecutor(this);
    Objects.requireNonNull(getCommand("kupdzialke")).setExecutor(this);
    Objects.requireNonNull(getCommand("dzialka")).setTabCompleter(this);
    Bukkit.getScheduler().runTaskLater(this,this::refreshDisplays,20L);
    getServer().getPluginManager().registerEvents(this,this);
    getLogger().info("Dzialka uruchomiona. Ekonomia: "+(eco==null?"BRAK Vault/Economy":"aktywna"));
  }
  @Override public void onDisable(){machineCounts.clear();borders.values().forEach(BukkitTask::cancel);for(UUID id:shownNight.keySet()){Player p=Bukkit.getPlayer(id);if(p!=null)p.resetPlayerTime();}save();}
  // Powiadomienia Discord są wysyłane poza głównym wątkiem serwera.
  // URL webhooka pozostaje wyłącznie w config.yml, nie trafia do logów.
  private void discord(String event, String message){
    if(!getConfig().getBoolean("discord.wlaczone",false) || !getConfig().getBoolean("discord.zdarzenia."+event,true))return;
    String url=getConfig().getString("discord.webhook-url","").trim();
    if(!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/"))return;
    String safe=message.replace("@","＠").replace("`","'");
    if(safe.length()>1800)safe=safe.substring(0,1800);
    String json="{\"content\":\""+discordEscape(safe)+"\",\"allowed_mentions\":{\"parse\":[]}}";
    Bukkit.getScheduler().runTaskAsynchronously(this,()->{
      try{
        var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
          .timeout(java.time.Duration.ofSeconds(8)).header("Content-Type","application/json; charset=utf-8")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
        var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()<200||response.statusCode()>=300)getLogger().warning("Discord: błąd HTTP "+response.statusCode()+" przy zdarzeniu "+event);
      }catch(Exception ex){getLogger().warning("Discord: nie udało się wysłać powiadomienia ("+event+").");}
    });
  }
  private String discordEscape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r"," ");}
  private void tell(CommandSender p,String s){p.sendMessage(color(messages.getString("prefix", "&8[&aDziałka&8] &7"))+s);}
  private String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
  private String msg(String key,String fallback){return color(messages.getString(key,fallback));}
  private void loadMessages(){messagesFile=new File(getDataFolder(),"messages.yml");if(!messagesFile.exists())saveResource("messages.yml",false);messages=YamlConfiguration.loadConfiguration(messagesFile);}
  private boolean migrateLegacyData(){
    File old=new File(getDataFolder().getParentFile(),"EasyMinePlots");
    File target=new File(getDataFolder(),"dzialki.yml");
    if(target.exists()||!old.isDirectory())return true;
    File legacy=new File(old,"dzialki.yml");
    if(!legacy.isFile())return true;
    if(!getDataFolder().exists()&&!getDataFolder().mkdirs())return false;
    try{
      java.nio.file.Files.copy(legacy.toPath(),target.toPath());
      File configOld=new File(old,"config.yml");
      File configNew=new File(getDataFolder(),"config.yml");
      if(configOld.isFile()&&!configNew.exists())java.nio.file.Files.copy(configOld.toPath(),configNew.toPath());
      getLogger().info("Migracja: skopiowano dane ze starego folderu EasyMinePlots. Oryginal pozostaje bez zmian.");
      return true;
    }catch(IOException ex){getLogger().severe("Migracja danych: "+ex.getMessage());return false;}
  }
  private void audit(String actor,String action,UUID plotId){
    File log=new File(getDataFolder(),"historia-dzialek.log");
    String stamp=java.time.OffsetDateTime.now().toString();
    String line=stamp+" | "+actor.replace('\n','_').replace('\r','_')+" | "+action.replace('\n','_').replace('\r','_')+" | "+(plotId==null?"-":plotId)+System.lineSeparator();
    try{java.nio.file.Files.writeString(log.toPath(),line,java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}
    catch(IOException ex){getLogger().warning("Nie udało się zapisać historii: "+ex.getMessage());}
  }
  private void backup(){if(!file.isFile())return;try{File dir=new File(getDataFolder(),"backups");dir.mkdirs();String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(java.time.LocalDateTime.now());java.nio.file.Files.copy(file.toPath(),new File(dir,"dzialki-"+stamp+".yml").toPath());File[] arr=dir.listFiles((d,n)->n.endsWith(".yml"));if(arr!=null&&arr.length>getConfig().getInt("maksymalna-liczba-kopii",20)){Arrays.sort(arr,Comparator.comparingLong(File::lastModified));for(int i=0;i<arr.length-getConfig().getInt("maksymalna-liczba-kopii",20);i++)arr[i].delete();}}catch(IOException ex){getLogger().warning("Kopia zapasowa: "+ex.getMessage());}}
  private String name(UUID id){String n=Bukkit.getOfflinePlayer(id).getName();return n==null?id.toString().substring(0,8):n;}
  private Plot at(Location l){for(Plot a:plots.values())if(a.contains(l))return a;return null;}
  private List<Plot> owned(UUID id){return plots.values().stream().filter(a->a.owner.equals(id)).toList();}
  private int limit(Player p){
    if(admin(p)||p.hasPermission("dzialka.limit.unlimited"))return Integer.MAX_VALUE;
    for(int n=5;n>=2;n--)if(p.hasPermission("dzialka.limit."+n))return n;
    return 1;
  }
  private Plot own(UUID id){
    UUID selected=selectedPlots.get(id);
    Plot found=selected==null?null:plots.get(selected);
    if(found!=null&&found.owner.equals(id))return found;
    List<Plot> list=owned(id);
    return list.isEmpty()?null:list.get(0);
  }
  private Plot current(Player p){Plot here=at(p.getLocation());return here!=null&&here.owner.equals(p.getUniqueId())?here:own(p.getUniqueId());}
  private void select(Player p,Plot a){selectedPlots.put(p.getUniqueId(),a.id);tell(p,"Wybrano działkę §e"+a.id.toString().substring(0,8)+"§7.");}
  private void listPlots(Player p){List<Plot> list=owned(p.getUniqueId());if(list.isEmpty()){tell(p,"Nie masz jeszcze działek.");return;}tell(p,"Twoje działki: §e"+list.size()+"/"+(limit(p)==Integer.MAX_VALUE?"∞":limit(p)));
    for(int i=0;i<list.size();i++){Plot a=list.get(i);tell(p,"§e"+(i+1)+"§7. "+a.world+" §f"+a.x+", "+a.y+", "+a.z+"§7"+(a==own(p.getUniqueId())?" §a[wybrana]":""));}
    tell(p,"Wybierz: §a/dzialka wybierz <numer>§7.");
  }
  // Połączenie logiczne: prostokąty muszą mieć wspólną krawędź (nie tylko róg).
  private boolean touching(Plot a,Plot b){
    boolean xEdge=(long)a.maxX()+1==b.minX()||(long)b.maxX()+1==a.minX();
    boolean zEdge=(long)a.maxZ()+1==b.minZ()||(long)b.maxZ()+1==a.minZ();
    boolean zOverlap=a.minZ()<=b.maxZ()&&b.minZ()<=a.maxZ();
    boolean xOverlap=a.minX()<=b.maxX()&&b.minX()<=a.maxX();
    return (xEdge&&zOverlap)||(zEdge&&xOverlap);
  }
  private boolean overlaps(Plot a,int ignoredLevel){
    int mnX=a.minX(),mnZ=a.minZ(),mxX=a.maxX(),mxZ=a.maxZ();
    for(Plot b:plots.values())if(b!=a&&b.world.equals(a.world)&&mnX<=b.maxX()&&mxX>=b.minX()&&mnZ<=b.maxZ()&&mxZ>=b.minZ())return true;
    return false;
  }
  private static final String[] SUBCOMMANDS={"sprzedaj","kup","anuluj","mechanizmy","lista","wybierz","blok","granice","home","ustawienia","czlonkowie","zapros","akceptuj","zastepca","degraduj","wyrzuc","info","reload","usun","bank","warp","odwiedz","polub","ranking","permisje","polacz","rozlacz","grupa","rozmiar"};
  @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
    if(!(sender instanceof Player p))return Collections.emptyList();
    Plot a=own(p.getUniqueId());List<String> choices=new ArrayList<>();
    if(args.length==1){for(String sub:SUBCOMMANDS){if(sub.equals("reload")&&!admin(p))continue;if((sub.equals("granice")||sub.equals("home")||sub.equals("ustawienia")||sub.equals("czlonkowie"))&&a==null)continue;if((sub.equals("zapros")||sub.equals("wyrzuc"))&&(a==null||!a.manager(p.getUniqueId())))continue;if((sub.equals("zastepca")||sub.equals("degraduj"))&&(a==null||!a.owner.equals(p.getUniqueId())))continue;choices.add(sub);}}
    else if(args.length==2){String sub=args[0].toLowerCase(Locale.ROOT);if(sub.equals("zapros")&&a!=null&&a.manager(p.getUniqueId())){for(Player other:Bukkit.getOnlinePlayers())if(!other.equals(p))choices.add(other.getName());}else if(a!=null){Set<UUID> ids=switch(sub){case "wyrzuc","zastepca","permisje" -> a.members;case "degraduj" -> a.deputies;default -> Collections.emptySet();};for(UUID id:ids)choices.add(name(id));}}
    String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
  }
  private boolean isAnchor(Block b,Plot a){return b.getWorld().getName().equals(a.world)&&b.getX()==a.x&&b.getY()==a.y&&b.getZ()==a.z;}
  private void spawnDisplay(Plot a){World w=Bukkit.getWorld(a.world);if(w==null)return;Location l=new Location(w,a.x+0.5,a.y+0.5,a.z+0.5);if(!l.isChunkLoaded())return;
    for(org.bukkit.entity.Entity ent:w.getNearbyEntities(l,1,1,1))if(ent instanceof ItemDisplay d&&(d.getPersistentDataContainer().has(displayKey,PersistentDataType.STRING)||d.getPersistentDataContainer().has(legacyDisplayKey,PersistentDataType.STRING)))d.remove();
    Block anchor=w.getBlockAt(a.x,a.y,a.z);
    // Ukryty blok kolizji; widoczny wyglad zapewnia ItemDisplay z resource packa.
    // Migruj tylko kotwice zarejestrowanych dzialek (nigdy innych magnetytow).
    if(anchor.getType()==Material.LODESTONE)anchor.setType(Material.BARRIER,false);
    if(anchor.getType()!=Material.BARRIER)return;
    w.spawn(l,ItemDisplay.class,d->{d.setItemStack(plotBlock());d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);d.setPersistent(true);d.setDisplayWidth(0);d.setDisplayHeight(0);d.getPersistentDataContainer().set(displayKey,PersistentDataType.STRING,a.owner.toString());});
  }
  // Etap 14: hologram jest encją TextDisplay z ID działki w PDC.
  // Usuwamy wyłącznie hologramy oznaczone naszym kluczem, nie cudze encje.
  private void removeHologram(Plot a){
    World w=Bukkit.getWorld(a.world);if(w==null)return;
    Location l=new Location(w,a.x+0.5,a.y+2.2,a.z+0.5);
    if(!l.isChunkLoaded())return;
    for(Entity ent:w.getNearbyEntities(l,1.5,2,1.5))
      if(ent instanceof TextDisplay d&&a.id.toString().equals(d.getPersistentDataContainer().get(hologramKey,PersistentDataType.STRING)))d.remove();
  }
  private void updateHologram(Plot a){
    World w=Bukkit.getWorld(a.world);if(w==null)return;
    Location l=new Location(w,a.x+0.5,a.y+2.2,a.z+0.5);
    if(!l.isChunkLoaded())return;
    removeHologram(a);
    if(!getConfig().getBoolean("hologramy.wlaczone",true))return;
    String title=a.title.isBlank()?"Działka gracza "+name(a.owner):a.title;
    String owner=name(a.owner);
    String label="§a§l"+title+"\n§7Właściciel: §f"+owner;
    if(getConfig().getBoolean("hologramy.pokaz-polubienia",true))label+="\n§d❤ §f"+a.likes.size();
    final String text=label;
    w.spawn(l,TextDisplay.class,d->{
      d.setText(text);d.setBillboard(Display.Billboard.CENTER);d.setSeeThrough(false);
      d.setShadowed(true);d.setDefaultBackground(false);d.setAlignment(TextDisplay.TextAlignment.CENTER);
      d.setPersistent(true);d.getPersistentDataContainer().set(hologramKey,PersistentDataType.STRING,a.id.toString());
    });
  }
  private void refreshDisplays(){for(Plot a:plots.values()){spawnDisplay(a);updateHologram(a);}}
  @EventHandler public void chunkLoad(org.bukkit.event.world.ChunkLoadEvent e){for(Plot a:plots.values())if(a.world.equals(e.getWorld().getName())&&Math.floorDiv(a.x,16)==e.getChunk().getX()&&Math.floorDiv(a.z,16)==e.getChunk().getZ())Bukkit.getScheduler().runTaskLater(this,()->{spawnDisplay(a);updateHologram(a);},1L);}
  private boolean admin(Player p){return p.hasPermission("dzialka.admin")||p.hasPermission("easymineplots.admin");}
  private ItemStack icon(Material m,String n,String... lore){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.setDisplayName(n);meta.setLore(Arrays.asList(lore));i.setItemMeta(meta);return i;}
  private ItemStack plotBlock(){ItemStack i=icon(Material.LODESTONE,"§a§lBlok Działki","§7Postaw, aby utworzyć działkę 16×16.","§7Zarządzanie: /dzialka");ItemMeta m=i.getItemMeta();m.getPersistentDataContainer().set(itemKey,PersistentDataType.BYTE,(byte)1);m.setItemModel(new NamespacedKey("easymine","blok_dzialki"));i.setItemMeta(m);return i;}
  private boolean isBlock(ItemStack i){return i!=null&&i.hasItemMeta()&&(i.getItemMeta().getPersistentDataContainer().has(itemKey,PersistentDataType.BYTE)||i.getItemMeta().getPersistentDataContainer().has(legacyItemKey,PersistentDataType.BYTE));}
  private void shop(Player p){
    Inventory inv=Bukkit.createInventory(null,27,SHOP);
    double price=Math.max(0,getConfig().getDouble("sklep.cena-bloku",10000));
    inv.setItem(13,icon(Material.LODESTONE,"§aKup Blok Działki","§7Cena: §e"+price+" $","§7Twój limit działek: §e"+(limit(p)==Integer.MAX_VALUE?"bez limitu":limit(p)),"§eKliknij, aby kupić"));
    inv.setItem(22,icon(Material.BARRIER,"§cZamknij"));
    p.openInventory(inv);
  }
  private void buyBlock(Player p){
    if(p.getInventory().firstEmpty()==-1){tell(p,"Zwolnij miejsce w ekwipunku.");return;}
    double price=Math.max(0,getConfig().getDouble("sklep.cena-bloku",10000));
    if(price>0){
      if(eco==null){tell(p,"Sklep wymaga Vault i aktywnej ekonomii.");return;}
      if(!eco.has(p,price)){tell(p,"Nie masz wystarczająco pieniędzy. Cena: §e"+price+" $§7.");return;}
      var response=eco.withdrawPlayer(p,price);
      if(!response.transactionSuccess()){tell(p,"Płatność nie powiodła się.");return;}
    }
    p.getInventory().addItem(plotBlock());
    tell(p,"Kupiono Blok Działki za §e"+price+" $§7.");
    discord("zakup-bloku","🧱 "+p.getName()+" kupił Blok Działki za "+price+" $");
  }
  private void menu(Player p){Inventory v=Bukkit.createInventory(null,27,MAIN);v.setItem(10,icon(Material.LODESTONE,"§aBlok Działki","§7Kup: /kupdzialke"));v.setItem(14,icon(Material.PLAYER_HEAD,"§eCzłonkowie","§7Zarządzaj członkami i zastępcami"));v.setItem(16,icon(Material.COMPARATOR,"§bUstawienia","§7Zmień zasady na działce"));v.setItem(22,icon(Material.LIME_STAINED_GLASS_PANE,"§aPokaż granice","§7Zielone cząsteczki przez 30 sekund"));if(current(p)!=null){v.setItem(18,icon(Material.GOLD_INGOT,"§6Bank działki","§7Saldo: §e"+String.format(Locale.US,"%.2f",current(p).bank),"§7/dzialka bank wplac <kwota>","§7/dzialka bank wyplac <kwota>"));v.setItem(20,icon(Material.ENDER_PEARL,"§bPubliczny warp","§7/dzialka warp wlacz/wylacz"));v.setItem(24,icon(Material.HEART_OF_THE_SEA,"§dPolubienia","§7Liczba: "+current(p).likes.size()));v.setItem(25,icon(Material.HOPPER,"§bUlepszenia mechanizmów","§7Redstone, hoppery i tłoki"));v.setItem(19,icon(Material.MAP,"§aPowiększ działkę","§7Rozmiar: "+current(p).size+"×"+current(p).size,"§7Kliknij, aby ulepszyć"));v.setItem(23,icon(Material.BRICKS,"§aWyzwania tygodniowe","§7Postęp i nagrody"));}if(current(p)!=null)v.setItem(26,icon(Material.BARRIER,"§cUsuń działkę","§7Wymaga potwierdzenia","§7Odzyskasz Blok Działki"));p.openInventory(v);}
  private void membersMenu(Player p){Inventory v=Bukkit.createInventory(null,27,MEMBERS);v.setItem(10,icon(Material.PLAYER_HEAD,"§aDodaj członka","§7Komenda: /dzialka zapros <nick>"));v.setItem(12,icon(Material.GOLDEN_HELMET,"§eNadaj zastępcę","§7Komenda: /dzialka zastepca <nick>"));v.setItem(14,icon(Material.BARRIER,"§cWyrzuć członka","§7Komenda: /dzialka wyrzuc <nick>"));v.setItem(16,icon(Material.BOOK,"§bLista członków","§7Komenda: /dzialka czlonkowie"));v.setItem(22,icon(Material.ARROW,"§7Powrót"));p.openInventory(v);}
  private void settingsMenu(Player p){Plot a=current(p);if(a==null){tell(p,"Nie masz działki.");return;}if(!a.owner.equals(p.getUniqueId())){tell(p,"Ustawienia może zmieniać tylko właściciel.");return;}Inventory v=Bukkit.createInventory(null,27,SETTINGS);for(int k=0;k<FLAGS.length;k++)v.setItem(k,icon(ICONS[k],"§e"+LABELS[k],"§7Stan: "+(a.flag(FLAGS[k])?"§aWŁ.":"§cWYŁ."),"§7Kliknij, aby zmienić"));v.setItem(22,icon(Material.ARROW,"§7Powrót"));p.openInventory(v);}
  private void border(Player p){Plot a=current(p);if(a==null){tell(p,"Nie masz działki.");return;}BukkitTask old=borders.remove(p.getUniqueId());if(old!=null)old.cancel();World w=Bukkit.getWorld(a.world);if(w==null)return;final int[] count={0};BukkitTask task=Bukkit.getScheduler().runTaskTimer(this,()->{if(!p.isOnline()||count[0]++>=30){BukkitTask t=borders.remove(p.getUniqueId());if(t!=null)t.cancel();return;}int y=p.getLocation().getBlockY()+1;for(int x=a.minX();x<=a.maxX()+1;x+=2){p.spawnParticle(Particle.DUST,new Location(w,x,y,a.minZ()),1,new Particle.DustOptions(Color.LIME,1.4f));p.spawnParticle(Particle.DUST,new Location(w,x,y,a.maxZ()+1),1,new Particle.DustOptions(Color.LIME,1.4f));}for(int z=a.minZ();z<=a.maxZ()+1;z+=2){p.spawnParticle(Particle.DUST,new Location(w,a.minX(),y,z),1,new Particle.DustOptions(Color.LIME,1.4f));p.spawnParticle(Particle.DUST,new Location(w,a.maxX()+1,y,z),1,new Particle.DustOptions(Color.LIME,1.4f));}},0L,20L);borders.put(p.getUniqueId(),task);tell(p,"Wyświetlam zielone granice przez 30 sekund.");}
  @Override public boolean onCommand(CommandSender sender,Command cmd,String alias,String[] args){if(cmd!=null&&cmd.getName().equalsIgnoreCase("kupdzialke")){if(sender instanceof Player buyer)shop(buyer);else tell(sender,"Tylko gracz może otworzyć sklep.");return true;}if(args.length>0&&args[0].equalsIgnoreCase("reload")){if(!sender.hasPermission("dzialka.admin")&&!sender.hasPermission("easymineplots.admin")){tell(sender,"Brak uprawnień.");return true;}reloadConfig();loadMessages();refreshDisplays();tell(sender,msg("reload","&aPrzeładowano config.yml i messages.yml."));return true;}if(!(sender instanceof Player p)){tell(sender,"Ta komenda wymaga gracza.");return true;}if(args.length==0){menu(p);return true;}String sub=args[0].toLowerCase(Locale.ROOT);Plot a=current(p);switch(sub){
    case "admin" -> {
      if(!admin(p)){tell(p,"Brak uprawnień administratora.");break;}
      if(args.length<2){tell(p,"/dzialka admin info | lista | backup | historia | usun <id>");break;}
      String action=args[1].toLowerCase(Locale.ROOT);
      if(action.equals("lista")){
        tell(p,"Wszystkie działki (§e"+plots.size()+"§7):");
        int shown=0;
        for(Plot plot:plots.values()){
          if(shown++>=15){tell(p,"Pokazano pierwsze 15 działek.");break;}
          tell(p,"§e"+plot.id+" §7- "+name(plot.owner)+" / "+plot.world+" / "+plot.x+", "+plot.z);
        }
      }else if(action.equals("info")){
        Plot plot=at(p.getLocation());
        if(plot==null){tell(p,"Stań na działce, którą chcesz sprawdzić.");break;}
        tell(p,"ID: §e"+plot.id+"§7; właściciel: §e"+name(plot.owner));
        tell(p,"Członkowie: §e"+plot.members.size()+"§7; zastępcy: §e"+plot.deputies.size());
      }else if(action.equals("backup")){
        if(!file.isFile()){tell(p,"Nie ma jeszcze pliku dzialki.yml.");break;}
        backup();tell(p,"Zlecono kopię zapasową w folderze backups. Sprawdź konsolę w razie błędu.");
      }else if(action.equals("historia")){
        File log=new File(getDataFolder(),"historia-dzialek.log");
        if(!log.isFile()){tell(p,"Historia jest jeszcze pusta.");break;}
        try{List<String> lines=java.nio.file.Files.readAllLines(log.toPath(),java.nio.charset.StandardCharsets.UTF_8);tell(p,"Ostatnie wpisy historii:");for(int i=Math.max(0,lines.size()-8);i<lines.size();i++)tell(p,"§7"+lines.get(i));}
        catch(IOException ex){tell(p,"Nie udało się odczytać historii.");}
      }else if(action.equals("usun")){
        if(args.length<3){tell(p,"Podaj pełne UUID działki z /dzialka admin lista.");break;}
        try{
          UUID id=UUID.fromString(args[2]);Plot plot=plots.get(id);
          if(plot==null){tell(p,"Nie znaleziono działki o takim ID.");break;}
          backup();
          World w=Bukkit.getWorld(plot.world);
          if(w!=null){Block anchor=w.getBlockAt(plot.x,plot.y,plot.z);if(anchor.getType()==Material.LODESTONE||anchor.getType()==Material.BARRIER)anchor.setType(Material.AIR,false);}
          removeHologram(plot);plots.remove(id);saleOffers.remove(id);selectedPlots.values().removeIf(id::equals);save();audit(p.getName(),"ADMIN_USUN",id);
          tell(p,"Usunięto działkę §e"+id+"§7. Kopia danych została wykonana.");
          getLogger().warning("Administrator "+p.getName()+" usunął działkę "+id+" gracza "+plot.owner);
        }catch(IllegalArgumentException ex){tell(p,"Niepoprawne UUID działki.");}
      }else tell(p,"/dzialka admin info | lista | backup | historia | usun <id>");
    }
    case "oferty" -> {openOffers(p);}
    case "sprzedaj" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Wybierz własną działkę.");break;}
      if(args.length!=3){tell(p,"Użycie: /dzialka sprzedaj <nick online> <cena>");break;}
      Player buyer=Bukkit.getPlayerExact(args[1]);
      if(buyer==null||buyer.equals(p)){tell(p,"Kupujący musi być online i nie może być właścicielem.");break;}
      double price;
      try{price=Double.parseDouble(args[2].replace(',','.'));}catch(NumberFormatException ex){tell(p,"Nieprawidłowa cena.");break;}
      if(!Double.isFinite(price)||price<=0||price>getConfig().getDouble("sprzedaz.maksymalna-cena",1000000000)){tell(p,"Cena musi być dodatnia i nie większa niż limit w config.yml.");break;}
      if(owned(buyer.getUniqueId()).size()>=limit(buyer)){tell(p,"Kupujący osiągnął limit działek.");break;}
      saleOffers.put(a.id,new SaleOffer(a.id,p.getUniqueId(),buyer.getUniqueId(),price,System.currentTimeMillis()+60000));
      tell(p,"Oferta sprzedaży za §e"+price+" $ §7wysłana do "+buyer.getName()+" (60 sekund).");
      tell(buyer,"Gracz §e"+p.getName()+" §7oferuje działkę §e"+a.id+" §7za §e"+price+" $§7. Potwierdź: §a/dzialka kup "+a.id+" §7lub otwórz §a/dzialka oferty");
    }
    case "anuluj" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Wybierz własną działkę.");break;}
      if(saleOffers.remove(a.id)!=null)tell(p,"Anulowano ofertę sprzedaży.");else tell(p,"Nie ma aktywnej oferty.");
    }
    case "kup" -> {
      if(args.length!=2){tell(p,"Użycie: /dzialka kup <UUID działki>");break;}
      UUID id;try{id=UUID.fromString(args[1]);}catch(IllegalArgumentException ex){tell(p,"Niepoprawne UUID.");break;}
      SaleOffer offer=saleOffers.get(id);Plot target=plots.get(id);
      if(offer==null||target==null||!offer.buyer().equals(p.getUniqueId())||!offer.seller().equals(target.owner)||System.currentTimeMillis()>offer.expires()){
        saleOffers.remove(id);tell(p,"Oferta nie istnieje lub wygasła.");break;
      }
      if(owned(p.getUniqueId()).size()>=limit(p)){tell(p,"Osiągnąłeś limit działek.");break;}
      Player seller=Bukkit.getPlayer(offer.seller());
      if(seller==null){tell(p,"Sprzedający musi być online.");break;}
      if(eco==null){tell(p,"Brak ekonomii Vault.");break;}
      if(!eco.has(p,offer.price())){tell(p,"Nie masz wystarczająco pieniędzy.");break;}
      var withdrawal=eco.withdrawPlayer(p,offer.price());
      if(!withdrawal.transactionSuccess()){tell(p,"Nie udało się pobrać pieniędzy.");break;}
      var deposit=eco.depositPlayer(seller,offer.price());
      if(!deposit.transactionSuccess()){
        var refund=eco.depositPlayer(p,offer.price());
        getLogger().severe("Nieudana wypłata sprzedaży "+id+". Zwrot kupującemu: "+refund.transactionSuccess());
        tell(p,"Transakcja nie powiodła się. Sprawdź saldo i zgłoś administratorowi.");break;
      }
      backup();UUID previousOwner=target.owner;
      target.owner=p.getUniqueId();target.members.clear();target.deputies.clear();target.memberPermissions.clear();target.likes.clear();target.publicWarp=false;
      selectedPlots.values().removeIf(id::equals);selectedPlots.put(p.getUniqueId(),id);saleOffers.remove(id);
      save();audit(p.getName(),"SPRZEDAZ_OD_"+previousOwner+"_ZA_"+offer.price(),id);
      discord("sprzedaz","💰 "+seller.getName()+" sprzedał działkę "+id+" graczowi "+p.getName()+" za "+offer.price()+" $");
      tell(p,"§aKupiono działkę za §e"+offer.price()+" $§a. Bank i ulepszenia działki pozostają na działce.");
      tell(seller,"§aSprzedano działkę graczowi §e"+p.getName()+" §aza §e"+offer.price()+" $§a.");
    }
    case "blok" -> {if(!admin(p)){tell(p,"Blok Działki kupisz przez /kupdzialke.");break;}if(owned(p.getUniqueId()).size()>=limit(p)){tell(p,"Osiągnięto limit działek.");break;}p.getInventory().addItem(plotBlock()).forEach((slot,item)->p.getWorld().dropItemNaturally(p.getLocation(),item));tell(p,"Otrzymano Blok Działki. Postaw go w wybranym miejscu.");}
    case "lista" -> listPlots(p);
    case "wybierz" -> {List<Plot> list=owned(p.getUniqueId());try{int i=Integer.parseInt(args[1])-1;if(i<0||i>=list.size())throw new IllegalArgumentException();select(p,list.get(i));}catch(Exception ex){tell(p,"Użycie: /dzialka wybierz <numer z /dzialka lista>");}}
    case "ulepsz" -> tell(p,"Ulepsz rozmiar: /dzialka rozmiar; mechanizmy: /dzialka mechanizmy.");
    case "rozmiar" -> sizeMenu(p);
    case "usun" -> openRemoval(p);
    case "granice" -> border(p);
    case "ustawienia" -> settingsMenu(p);
    case "czlonkowie" -> {if(a==null){tell(p,"Nie masz działki.");break;}tell(p,"Właściciel: §e"+name(a.owner));tell(p,"Zastępcy: §e"+names(a.deputies));tell(p,"Członkowie: §e"+names(a.members));}
    case "home" -> {if(a==null){tell(p,"Nie masz działki.");break;}World w=Bukkit.getWorld(a.world);if(w!=null)p.teleport(new Location(w,a.x+0.5,a.y+1,a.z+0.5));}
    case "zapros" -> {if(a==null||!a.manager(p.getUniqueId())){tell(p,"Nie możesz zapraszać.");break;}Player target=args.length>1?Bukkit.getPlayerExact(args[1]):null;if(target==null||target.equals(p)){tell(p,"Użycie: /dzialka zapros <gracz online>");break;}invites.put(target.getUniqueId(),a.id);inviteExpiry.put(target.getUniqueId(),System.currentTimeMillis()+60000);tell(target,"§e"+name(a.owner)+" §7zaprasza Cię na działkę. Wpisz §a/dzialka akceptuj §7w ciągu 60 sekund.");tell(p,"Wysłano zaproszenie.");}
    case "akceptuj" -> {UUID owner=invites.remove(p.getUniqueId());Long expires=inviteExpiry.remove(p.getUniqueId());Plot target=owner==null?null:plots.get(owner);if(target==null||expires==null||System.currentTimeMillis()>expires){tell(p,"Nie masz ważnego zaproszenia.");break;}target.members.add(p.getUniqueId());target.memberPermissions.put(p.getUniqueId(),new HashSet<>(Arrays.asList("budowanie","interakcje")));save();tell(p,"Dołączono do działki gracza "+name(target.owner)+".");}
    case "zastepca" -> {if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może nadawać zastępcę.");break;}UUID id=resolve(args);if(id==null||!a.members.remove(id)){tell(p,"Gracz musi być zwykłym członkiem.");break;}a.deputies.add(id);a.memberPermissions.remove(id);save();tell(p,"Awansowano "+name(id)+" na zastępcę.");}
    case "degraduj" -> {if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może degradować zastępców.");break;}UUID id=resolve(args);if(id==null||!a.deputies.remove(id)){tell(p,"Ten gracz nie jest zastępcą.");break;}a.members.add(id);a.memberPermissions.put(id,new HashSet<>(Arrays.asList("budowanie","interakcje")));save();tell(p,"Zdegradowano zastępcę.");}
    case "wyrzuc" -> {if(a==null||!a.manager(p.getUniqueId())){tell(p,"Nie masz uprawnień.");break;}UUID id=resolve(args);if(id==null){tell(p,"Użycie: /dzialka wyrzuc <nick>");break;}if(a.deputies.contains(id)){tell(p,"Zastępcę może usunąć wyłącznie właściciel przez /dzialka degraduj.");break;}if(!a.members.remove(id)){tell(p,"Gracz nie jest członkiem.");break;}a.memberPermissions.remove(id);save();tell(p,"Usunięto gracza z działki.");}
    case "permisje" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może ustawiać uprawnienia członków.");break;}
      if(args.length<2){tell(p,"Użycie: /dzialka permisje <nick> [budowanie|interakcje] [tak|nie]");break;}
      UUID member=resolve(new String[]{"permisje",args[1]});
      if(member==null||!a.members.contains(member)){tell(p,"Ten gracz nie jest zwykłym członkiem działki.");break;}
      Set<String> perms=a.memberPermissions.computeIfAbsent(member,k->new HashSet<>());
      if(args.length==2){tell(p,"Uprawnienia "+name(member)+": budowanie="+perms.contains("budowanie")+", interakcje="+perms.contains("interakcje"));break;}
      String permission=args[2].toLowerCase(Locale.ROOT);
      if(!permission.equals("budowanie")&&!permission.equals("interakcje")){tell(p,"Dozwolone: budowanie, interakcje.");break;}
      if(args.length<4||(!args[3].equalsIgnoreCase("tak")&&!args[3].equalsIgnoreCase("nie"))){tell(p,"Użycie: /dzialka permisje <nick> "+permission+" <tak|nie>");break;}
      if(args[3].equalsIgnoreCase("tak"))perms.add(permission);else perms.remove(permission);
      save();tell(p,"Zmieniono "+permission+" dla "+name(member)+" na "+args[3]+".");
    }
    case "wyzwania" -> {challengeMenu(p);}
    case "mechanizmy" -> {upgradesMenu(p);}
    case "bank" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Musisz posiadać wybraną działkę.");break;}
      if(args.length==1){tell(p,"Bank działki: §e"+String.format(Locale.US,"%.2f",a.bank)+" $§7. /dzialka bank wplac <kwota> lub wyplac <kwota>");break;}
      if(args.length!=3||(!args[1].equalsIgnoreCase("wplac")&&!args[1].equalsIgnoreCase("wyplac"))){tell(p,"Użycie: /dzialka bank wplac/wyplac <kwota>");break;}
      double amount;try{amount=Double.parseDouble(args[2].replace(',','.'));}catch(NumberFormatException ex){tell(p,"Nieprawidłowa kwota.");break;}
      if(!Double.isFinite(amount)||amount<=0||amount>1000000000){tell(p,"Kwota musi być dodatnia i nie większa niż miliard.");break;}
      if(eco==null){tell(p,"Bank wymaga Vault i pluginu ekonomii.");break;}
      if(args[1].equalsIgnoreCase("wplac")){
        if(!eco.has(p,amount)||!eco.withdrawPlayer(p,amount).transactionSuccess()){tell(p,"Nie udało się pobrać pieniędzy.");break;}
        a.bank+=amount;
      }else{
        if(a.bank<amount){tell(p,"Brak środków w banku działki.");break;}
        if(!eco.depositPlayer(p,amount).transactionSuccess()){tell(p,"Nie udało się wypłacić pieniędzy.");break;}
        a.bank-=amount;
      }
      save();tell(p,"Saldo banku działki: §e"+String.format(Locale.US,"%.2f",a.bank)+" $§7.");
    }
    case "warp" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Musisz posiadać wybraną działkę.");break;}
      if(args.length!=2||(!args[1].equalsIgnoreCase("wlacz")&&!args[1].equalsIgnoreCase("wylacz"))){tell(p,"Użycie: /dzialka warp wlacz/wylacz");break;}
      a.publicWarp=args[1].equalsIgnoreCase("wlacz");save();tell(p,"Publiczny warp: "+(a.publicWarp?"§awłączony":"§cwyłączony"));
    }
    case "odwiedz" -> {
      if(args.length!=2){tell(p,"Użycie: /dzialka odwiedz <nick właściciela>");break;}
      UUID target=Bukkit.getOfflinePlayer(args[1]).getUniqueId();Plot dest=owned(target).stream().filter(q->q.publicWarp&&q.flag("wejscie")).findFirst().orElse(null);
      if(dest==null){tell(p,"Ten gracz nie udostępnia publicznej działki.");break;}
      World w=Bukkit.getWorld(dest.world);if(w==null){tell(p,"Świat działki nie jest dostępny.");break;}
      p.teleport(new Location(w,dest.x+0.5,dest.y+1,dest.z+0.5));tell(p,"Odwiedzasz działkę gracza §e"+name(dest.owner));
    }
    case "polub" -> {
      Plot target=at(p.getLocation());if(target==null){tell(p,"Musisz stać na działce, którą chcesz polubić.");break;}
      if(target.owner.equals(p.getUniqueId())){tell(p,"Nie możesz polubić własnej działki.");break;}
      if(!target.likes.add(p.getUniqueId())){tell(p,"Już polubiłeś tę działkę.");break;}
      save();tell(p,"Polubiono działkę! Polubienia: §e"+target.likes.size());
    }
    case "ranking" -> {
      tell(p,"§6Ranking działek według polubień:");
      List<Plot> top=new ArrayList<>(plots.values());top.sort(Comparator.comparingInt((Plot q)->q.likes.size()).reversed());
      for(int i=0;i<Math.min(10,top.size());i++)tell(p,"§e"+(i+1)+". §f"+name(top.get(i).owner)+" §7— §d"+top.get(i).likes.size()+" ♥");
    }
    case "nazwa", "opis" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Musisz być właścicielem wybranej działki.");break;}
      if(args.length<2){tell(p,"Użycie: /dzialka "+sub+" <tekst> (lub 'usun')");break;}
      String value=String.join(" ",Arrays.copyOfRange(args,1,args.length)).trim();
      if(value.equalsIgnoreCase("usun"))value="";
      int max=sub.equals("nazwa")?32:120;
      if(value.length()>max){tell(p,"Maksymalna długość: "+max+" znaków.");break;}
      value=ChatColor.stripColor(color(value.replace('§',' ')));
      if(sub.equals("nazwa"))a.title=value;else a.description=value;
      save();updateHologram(a);tell(p,"Zmieniono "+(sub.equals("nazwa")?"nazwę":"opis")+" działki.");
    }
    case "polacz" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Najpierw wybierz swoją działkę.");break;}
      if(!getConfig().getBoolean("laczenie.wlaczone",true)){tell(p,"Łączenie działek jest wyłączone.");break;}
      if(args.length<2){tell(p,"Użycie: /dzialka polacz <numer z /dzialka lista>");break;}
      Plot target;
      try{int index=Integer.parseInt(args[1])-1;List<Plot> list=owned(p.getUniqueId());target=index>=0&&index<list.size()?list.get(index):null;}catch(NumberFormatException ex){target=null;}
      if(target==null||target==a){tell(p,"Wybierz inną swoją działkę z /dzialka lista.");break;}
      if(!a.world.equals(target.world)||!touching(a,target)){tell(p,"Działki muszą stykać się bokami w tym samym świecie.");break;}
      if(a.groupId!=null&&target.groupId!=null&&a.groupId.equals(target.groupId)){tell(p,"Te działki są już połączone.");break;}
      UUID left=a.groupId==null?a.id:a.groupId,right=target.groupId==null?target.id:target.groupId;
      for(Plot plot:plots.values())if(plot.owner.equals(p.getUniqueId())&&(plot.id.equals(right)||right.equals(plot.groupId)))plot.groupId=left;
      for(Plot plot:plots.values())if(plot.owner.equals(p.getUniqueId())&&(plot.id.equals(left)||left.equals(plot.groupId)))plot.groupId=left;
      save();tell(p,"§aPołączono działki w grupę. Każda nadal zachowuje własny teren, bank i limity mechanizmów.");
    }
    case "rozlacz" -> {
      if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Wybierz swoją działkę.");break;}
      if(a.groupId==null){tell(p,"Ta działka nie należy do grupy.");break;}
      UUID old=a.groupId;a.groupId=null;
      List<Plot> rest=new ArrayList<>();for(Plot plot:plots.values())if(plot.owner.equals(p.getUniqueId())&&old.equals(plot.groupId))rest.add(plot);
      if(rest.size()==1)rest.get(0).groupId=null;
      save();tell(p,"§aOdłączono wybraną działkę od grupy.");
    }
    case "grupa" -> {
      if(a==null){tell(p,"Nie masz działki.");break;}
      if(a.groupId==null){tell(p,"Wybrana działka nie należy do grupy.");break;}
      tell(p,"Grupa działek: §e"+a.groupId.toString().substring(0,8));
      for(Plot plot:plots.values())if(a.groupId.equals(plot.groupId))tell(p,"§7- "+plot.id.toString().substring(0,8)+" §f"+plot.world+" "+plot.x+", "+plot.z);
    }
    case "info" -> {Plot here=at(p.getLocation());if(here==null){tell(p,"Nie stoisz na działce.");break;}tell(p,"Działka: §e"+(here.title.isBlank()?name(here.owner):here.title)+"§7, właściciel: §e"+name(here.owner)+"§7");if(!here.description.isBlank())tell(p,"Opis: §f"+here.description);}
    default -> tell(p,"Komendy: /dzialka, polacz, rozlacz, grupa, wyzwania, oferty, sprzedaj, kup, anuluj, lista, wybierz, nazwa, opis, bank, warp, odwiedz, polub, ranking, mechanizmy, granice, home, ustawienia, zapros, akceptuj, zastepca, degraduj, wyrzuc, czlonkowie, info.");
  }return true;}
  // Jedno wyzwanie na działkę w tygodniu ISO (UTC). Nagroda trafia do banku działki.
  private String challengeWeek(){java.time.LocalDate d=java.time.LocalDate.now(java.time.ZoneOffset.UTC);java.time.temporal.WeekFields wf=java.time.temporal.WeekFields.ISO;return d.get(wf.weekBasedYear())+"-W"+String.format(Locale.ROOT,"%02d",d.get(wf.weekOfWeekBasedYear()));}
  private void refreshChallenge(Plot a){String week=challengeWeek();if(!week.equals(a.challengeWeek)){a.challengeWeek=week;a.challengeBlocks=0;a.challengeClaimed=false;save();}}
  private int challengeTarget(){return Math.max(1,getConfig().getInt("wyzwania-tygodniowe.postaw-bloki.cel",250));}
  private double challengeReward(){return getConfig().getDouble("wyzwania-tygodniowe.postaw-bloki.nagroda",5000);}
  private void challengeMenu(Player p){Plot a=current(p);if(a==null){tell(p,"Najpierw wybierz działkę.");return;}refreshChallenge(a);Inventory v=Bukkit.createInventory(null,27,CHALLENGES);v.setItem(13,icon(Material.BRICKS,"§aPostaw bloki na działce","§7Tydzień: §e"+a.challengeWeek,"§7Postęp: §e"+Math.min(a.challengeBlocks,challengeTarget())+"/"+challengeTarget(),"§7Nagroda do banku: §6"+challengeReward()+" $",a.challengeClaimed?"§aNagroda odebrana":a.challengeBlocks>=challengeTarget()?"§aKliknij, aby odebrać!":"§eBuduj na swojej działce"));v.setItem(22,icon(Material.ARROW,"§7Powrót"));p.openInventory(v);}
  private void claimChallenge(Player p){Plot a=current(p);if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Nagrodę odbiera właściciel działki.");return;}if(!getConfig().getBoolean("wyzwania-tygodniowe.wlaczone",true)){tell(p,"Wyzwania są wyłączone.");return;}refreshChallenge(a);if(a.challengeClaimed){tell(p,"Nagroda za ten tydzień została już odebrana.");return;}if(a.challengeBlocks<challengeTarget()){tell(p,"Jeszcze nie ukończono wyzwania.");return;}double reward=challengeReward();if(!Double.isFinite(reward)||reward<0){tell(p,"Nieprawidłowa nagroda w config.yml.");return;}a.challengeClaimed=true;a.bank+=reward;save();audit(p.getName(),"WYZWANIE_TYGODNIOWE_"+a.challengeWeek,a.id);tell(p,"§aOdebrano "+reward+" $ do banku działki!");challengeMenu(p);}
  // Ulepszenia rozmiaru są niezależne od usuniętego systemu poziomów działki.
  private List<Integer> sizeTiers(){
    List<Integer> tiers=new ArrayList<>();
    for(String key:getConfig().getConfigurationSection("rozmiar-dzialki.ulepszenia")==null?Collections.<String>emptySet():getConfig().getConfigurationSection("rozmiar-dzialki.ulepszenia").getKeys(false)){
      try{int size=Integer.parseInt(key);if(size>32&&size<=250&&size%2==0)tiers.add(size);}catch(NumberFormatException ignored){}
    }
    Collections.sort(tiers);return tiers;
  }
  private int nextSize(Plot a){for(int size:sizeTiers())if(size>a.size)return size;return -1;}
  private void sizeMenu(Player p){Plot a=current(p);if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Wybierz własną działkę.");return;}Inventory v=Bukkit.createInventory(null,27,SIZE_MENU);int next=nextSize(a);
    v.setItem(13,icon(Material.MAP,"§aPowiększ działkę","§7Obecny rozmiar: "+a.size+"×"+a.size,next<0?"§aOsiągnięto maksimum":"§7Następny: "+next+"×"+next,next<0?"§7Brak kolejnych ulepszeń":"§eCena: "+getConfig().getDouble("rozmiar-dzialki.ulepszenia."+next+".cena",-1)+" $"));
    v.setItem(22,icon(Material.ARROW,"§cWróć"));p.openInventory(v);
  }
  private void sizePaymentMenu(Player p,Plot a){if(a==null||!a.owner.equals(p.getUniqueId()))return;int next=nextSize(a);if(next<0)return;double price=getConfig().getDouble("rozmiar-dzialki.ulepszenia."+next+".cena",-1);
    Inventory v=Bukkit.createInventory(null,27,SIZE_PAYMENT);v.setItem(11,icon(Material.GOLD_INGOT,"§aBank działki","§7Cena: "+price+" $","§7Saldo: "+a.bank+" $"));v.setItem(15,icon(Material.EMERALD,"§aKonto gracza","§7Cena: "+price+" $"));v.setItem(22,icon(Material.ARROW,"§cWróć"));p.openInventory(v);
  }
  private void buySize(Player p,Plot a,boolean bank){if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może powiększać działkę.");return;}int next=nextSize(a);if(next<0){tell(p,"Maksymalny rozmiar działki.");return;}
    if(!getConfig().getBoolean("rozmiar-dzialki.wlaczone",true)){tell(p,"Ulepszanie rozmiaru jest wyłączone.");return;}
    double price=getConfig().getDouble("rozmiar-dzialki.ulepszenia."+next+".cena",-1);
    if(!Double.isFinite(price)||price<0){tell(p,"Nieprawidłowa cena w config.yml.");return;}
    int old=a.size;a.size=next;boolean collision=overlaps(a,0);a.size=old;
    if(collision){tell(p,"Nie można powiększyć: nowy teren nachodzi na inną działkę.");return;}
    if(bank){if(!getConfig().getBoolean("rozmiar-dzialki.platnosc.bank-dzialki",true)){tell(p,"Płatność z banku jest wyłączona.");return;}if(a.bank<price){tell(p,"Za mało środków w banku działki.");return;}a.bank-=price;}
    else{if(!getConfig().getBoolean("rozmiar-dzialki.platnosc.konto-gracza",true)){tell(p,"Płatność z konta jest wyłączona.");return;}if(eco==null||!eco.has(p,price)){tell(p,"Brak ekonomii lub pieniędzy.");return;}if(price>0&&!eco.withdrawPlayer(p,price).transactionSuccess()){tell(p,"Płatność nie powiodła się.");return;}}
    a.size=next;save();pendingSize.remove(p.getUniqueId());audit(p.getName(),"POWIEKSZENIE_"+old+"_NA_"+next,a.id);tell(p,"§aPowiększono działkę do "+next+"×"+next+" za "+price+" $.");sizeMenu(p);
  }
  private int machineLimit(Plot a,String kind){return getConfig().getInt("ulepszenia."+kind+".poziomy."+a.machineLevels.getOrDefault(kind,1)+".limit",-1);}
  private String machineType(Material m){if(m==Material.HOPPER)return "hoppery";if(m==Material.PISTON||m==Material.STICKY_PISTON)return "tloki";if(m==Material.REDSTONE_WIRE||m==Material.REPEATER||m==Material.COMPARATOR||m==Material.OBSERVER)return "redstone";return null;}
  private void invalidateMachineCounts(){machineCounts.clear();}
  private int cachedMachines(Plot a,String kind,Block placed){
    Map<String,Integer> counts=machineCounts.computeIfAbsent(a.id,id->new HashMap<>());
    Integer known=counts.get(kind);
    if(known!=null)return known;
    // W BlockPlaceEvent blok jest już widoczny w świecie: odejmujemy go od stanu sprzed zdarzenia.
    int scanned=countMachines(a,kind);
    if(placed!=null && kind.equals(machineType(placed.getType())))scanned=Math.max(0,scanned-1);
    // Nie zapisuj niekompletnego wyniku: część chunków może być wyładowana.
    if(scanned>=0)counts.put(kind,scanned);
    return scanned;
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void trackMachinePlace(BlockPlaceEvent e){
    if(isBlock(e.getItemInHand()))return;
    String kind=machineType(e.getBlockPlaced().getType());
    Plot a=at(e.getBlockPlaced().getLocation());
    if(kind==null||a==null)return;
    Map<String,Integer> counts=machineCounts.get(a.id);
    if(counts!=null&&counts.containsKey(kind))counts.put(kind,counts.get(kind)+1);
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void trackMachineBreak(BlockBreakEvent e){
    String kind=machineType(e.getBlock().getType());Plot a=at(e.getBlock().getLocation());
    if(kind==null||a==null)return;
    Map<String,Integer> counts=machineCounts.get(a.id);
    if(counts!=null&&counts.containsKey(kind))counts.put(kind,Math.max(0,counts.get(kind)-1));
  }
  // Zmiany poza zwykłym stawianiem/niszczeniem wymagają ponownego przeliczenia.
  @EventHandler(priority=EventPriority.MONITOR) public void machineExplosion(EntityExplodeEvent e){invalidateMachineCounts();}
  @EventHandler(priority=EventPriority.MONITOR) public void machineBlockExplosion(BlockExplodeEvent e){invalidateMachineCounts();}
  @EventHandler(priority=EventPriority.MONITOR) public void machinePistonExtend(BlockPistonExtendEvent e){invalidateMachineCounts();}
  @EventHandler(priority=EventPriority.MONITOR) public void machinePistonRetract(BlockPistonRetractEvent e){invalidateMachineCounts();}
  @EventHandler public void machineChunkLoad(org.bukkit.event.world.ChunkLoadEvent e){invalidateMachineCounts();}
  @EventHandler public void machineChunkUnload(org.bukkit.event.world.ChunkUnloadEvent e){invalidateMachineCounts();}
  private int countMachines(Plot a,String kind){World w=Bukkit.getWorld(a.world);if(w==null)return 0;int count=0;for(int cx=a.minX()>>4;cx<=a.maxX()>>4;cx++)for(int cz=a.minZ()>>4;cz<=a.maxZ()>>4;cz++){if(!w.isChunkLoaded(cx,cz))return -1;for(int x=Math.max(a.minX(),cx<<4);x<=Math.min(a.maxX(),(cx<<4)+15);x++)for(int z=Math.max(a.minZ(),cz<<4);z<=Math.min(a.maxZ(),(cz<<4)+15);z++)for(int y=w.getMinHeight();y<w.getMaxHeight();y++)if(kind.equals(machineType(w.getBlockAt(x,y,z).getType())))count++;}return count;}
  private void upgradesMenu(Player p){Plot a=current(p);if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Wybierz własną działkę.");return;}Inventory v=Bukkit.createInventory(null,27,UPGRADES);Material[] mats={Material.HOPPER,Material.PISTON,Material.REPEATER};int[] slots={11,13,15};for(int i=0;i<MACHINES.length;i++){String k=MACHINES[i];int level=a.machineLevels.getOrDefault(k,1),next=level+1;String base="ulepszenia."+k+".poziomy.";String cost=getConfig().contains(base+next+".limit")?String.valueOf(getConfig().getDouble(base+next+".cena",0))+" $":"MAX";v.setItem(slots[i],icon(mats[i],"§a"+k,"§7Poziom: "+level,"§7Limit: "+machineLimit(a,k),"§7Następny: "+cost,"§eKliknij, aby ulepszyć"));}v.setItem(22,icon(Material.ARROW,"§cWróć"));p.openInventory(v);}
  private void paymentMenu(Player p,Plot a,String kind){int next=a.machineLevels.getOrDefault(kind,1)+1;double price=getConfig().getDouble("ulepszenia."+kind+".poziomy."+next+".cena",-1);Inventory v=Bukkit.createInventory(null,27,PAYMENT);v.setItem(11,icon(Material.GOLD_INGOT,"§aZapłać z banku działki","§7Cena: "+price+" $","§7Saldo: "+a.bank+" $"));v.setItem(15,icon(Material.EMERALD,"§aZapłać z konta gracza","§7Cena: "+price+" $"));v.setItem(22,icon(Material.ARROW,"§cWróć"));p.openInventory(v);}
  private void buyMachineUpgrade(Player p,Plot a,String kind,boolean fromBank){if(a==null||!a.owner.equals(p.getUniqueId()))return;if(!getConfig().getBoolean("ulepszenia."+kind+".wlaczone",true))return;int next=a.machineLevels.getOrDefault(kind,1)+1;String path="ulepszenia."+kind+".poziomy."+next;if(!getConfig().contains(path+".limit")){tell(p,"Maksymalny poziom.");return;}int newLimit=getConfig().getInt(path+".limit",-1);if(newLimit<=machineLimit(a,kind)){tell(p,"Nieprawidłowy limit ulepszenia w config.yml.");return;}double price=getConfig().getDouble(path+".cena",-1);if(!Double.isFinite(price)||price<0){tell(p,"Nieprawidłowa cena w config.yml.");return;}if(fromBank){if(!getConfig().getBoolean("ulepszenia.platnosc.bank-dzialki",true)){tell(p,"Płatność z banku wyłączona.");return;}if(a.bank<price){tell(p,"Za mało pieniędzy w banku.");return;}a.bank-=price;}else{if(!getConfig().getBoolean("ulepszenia.platnosc.konto-gracza",true)){tell(p,"Płatność z konta wyłączona.");return;}if(eco==null||!eco.has(p,price)){tell(p,"Brak ekonomii lub środków.");return;}if(price>0&&!eco.withdrawPlayer(p,price).transactionSuccess()){tell(p,"Płatność nie powiodła się.");return;}}a.machineLevels.put(kind,next);save();audit(p.getName(),"ULEPSZENIE_"+kind+"_"+next,a.id);discord("ulepszenie","⚙️ "+p.getName()+" ulepszył "+kind+" do poziomu "+next+" na działce "+a.id+" za "+price+" $");pendingUpgrade.remove(p.getUniqueId());pendingUpgradePlot.remove(p.getUniqueId());tell(p,"Ulepszono "+kind+" do poziomu §a"+next+"§7 za "+price+" $.");upgradesMenu(p);}
  private void openOffers(Player p){
    long now=System.currentTimeMillis();
    // Usuń tylko oferty wygasłe. Pozostałe są sprawdzane ponownie przy zakupie.
    saleOffers.entrySet().removeIf(e->e.getValue().expires()<now || !plots.containsKey(e.getKey()));
    List<UUID> ids=new ArrayList<>();
    for(SaleOffer offer:saleOffers.values())if(offer.buyer().equals(p.getUniqueId())&&offer.expires()>=now&&plots.containsKey(offer.plotId()))ids.add(offer.plotId());
    ids.sort(Comparator.comparing(UUID::toString));
    offerMenuRows.put(p.getUniqueId(),ids.subList(0,Math.min(45,ids.size())));
    Inventory inv=Bukkit.createInventory(null,54,OFFERS_MENU);
    for(int i=0;i<Math.min(45,ids.size());i++){
      SaleOffer offer=saleOffers.get(ids.get(i));Plot plot=plots.get(ids.get(i));
      if(offer==null||plot==null)continue;
      inv.setItem(i,icon(Material.EMERALD,"§aKup działkę od "+name(offer.seller()),
        "§7Cena: §e"+String.format(Locale.US,"%.2f",offer.price())+" $",
        "§7Nazwa: §f"+(plot.title.isBlank()?"Bez nazwy":plot.title),
        "§7Ważna jeszcze: §f"+Math.max(0,(offer.expires()-now+999)/1000)+" sekund",
        "§eKliknij, aby potwierdzić zakup"));
    }
    if(ids.isEmpty())inv.setItem(22,icon(Material.BARRIER,"§cBrak ofert dla Ciebie"));
    inv.setItem(49,icon(Material.ARROW,"§cZamknij"));p.openInventory(inv);
  }
  private void openRemoval(Player p){Plot a=current(p);if(a==null){tell(p,"Nie masz działki.");return;}pendingRemoval.add(p.getUniqueId());Inventory inv=Bukkit.createInventory(null,27,CONFIRM);inv.setItem(11,icon(Material.LIME_CONCRETE,"§aPOTWIERDŹ","§7Usuń ochronę i odzyskaj blok","§cUlepszenia nie są zwracane"));inv.setItem(15,icon(Material.RED_CONCRETE,"§cANULUJ"));p.openInventory(inv);}
  private void removeOwnPlot(Player p){Plot a=current(p);if(a==null){p.closeInventory();return;}World w=Bukkit.getWorld(a.world);if(w==null){tell(p,"Świat działki nie jest dostępny.");p.closeInventory();return;}Block b=w.getBlockAt(a.x,a.y,a.z);if(b.getType()!=Material.LODESTONE&&b.getType()!=Material.BARRIER){tell(p,"Blok działki został zmieniony. Skontaktuj się z administracją.");p.closeInventory();return;}
    backup();Location l=new Location(w,a.x+0.5,a.y+0.5,a.z+0.5);for(Entity ent:w.getNearbyEntities(l,1,1,1))if(ent instanceof ItemDisplay d&&(d.getPersistentDataContainer().has(displayKey,PersistentDataType.STRING)||d.getPersistentDataContainer().has(legacyDisplayKey,PersistentDataType.STRING)))d.remove();removeHologram(a);b.setType(Material.AIR,false);plots.remove(a.id);saleOffers.remove(a.id);selectedPlots.remove(p.getUniqueId());save();p.getInventory().addItem(plotBlock()).forEach((slot,item)->w.dropItemNaturally(p.getLocation(),item));p.closeInventory();tell(p,msg("dzialka-usunieta","&aDziałka usunięta. Odzyskano Blok Działki."));}
  private UUID resolve(String[] args){if(args.length<2)return null;Player online=Bukkit.getPlayerExact(args[1]);return online!=null?online.getUniqueId():Bukkit.getOfflinePlayer(args[1]).getUniqueId();}
  private String names(Set<UUID> ids){if(ids.isEmpty())return "brak";List<String> n=new ArrayList<>();for(UUID id:ids)n.add(name(id));return String.join(", ",n);}
  @EventHandler public void onGui(InventoryClickEvent e){String title=e.getView().getTitle();if(!title.equals(MAIN)&&!title.equals(MEMBERS)&&!title.equals(SETTINGS)&&!title.equals(CONFIRM)&&!title.equals(SHOP)&&!title.equals(UPGRADES)&&!title.equals(PAYMENT)&&!title.equals(OFFERS_MENU)&&!title.equals(CHALLENGES)&&!title.equals(SIZE_MENU)&&!title.equals(SIZE_PAYMENT))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;int slot=e.getRawSlot();if(slot<0||slot>=e.getView().getTopInventory().getSize())return;
    if(title.equals(SIZE_MENU)){if(slot==22){menu(p);return;}if(slot==13){Plot plot=current(p);if(plot!=null&&plot.owner.equals(p.getUniqueId())&&nextSize(plot)>0){pendingSize.put(p.getUniqueId(),plot.id);sizePaymentMenu(p,plot);}else tell(p,"Nie można powiększyć działki.");}return;}
    if(title.equals(SIZE_PAYMENT)){if(slot==22){sizeMenu(p);return;}if(slot!=11&&slot!=15)return;UUID id=pendingSize.get(p.getUniqueId());Plot plot=id==null?null:plots.get(id);if(plot==null||!plot.owner.equals(p.getUniqueId())){p.closeInventory();return;}buySize(p,plot,slot==11);return;}
    if(title.equals(CHALLENGES)){if(slot==22)menu(p);else if(slot==13)claimChallenge(p);return;}
    if(title.equals(UPGRADES)){if(slot==22){menu(p);return;}String kind=slot==11?"hoppery":slot==13?"tloki":slot==15?"redstone":null;if(kind==null)return;Plot plot=current(p);if(plot==null||!plot.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może kupować ulepszenia.");return;}if(!getConfig().getBoolean("ulepszenia."+kind+".wlaczone",true)){tell(p,"To ulepszenie jest wyłączone.");return;}int next=plot.machineLevels.getOrDefault(kind,1)+1;if(!getConfig().contains("ulepszenia."+kind+".poziomy."+next+".limit")){tell(p,"Osiągnięto maksymalny poziom.");return;}pendingUpgrade.put(p.getUniqueId(),kind);pendingUpgradePlot.put(p.getUniqueId(),plot.id);paymentMenu(p,plot,kind);return;}
    if(title.equals(PAYMENT)){if(slot==22){upgradesMenu(p);return;}if(slot!=11&&slot!=15)return;String kind=pendingUpgrade.get(p.getUniqueId());UUID id=pendingUpgradePlot.get(p.getUniqueId());Plot plot=id==null?null:plots.get(id);if(kind==null||plot==null||!plot.owner.equals(p.getUniqueId())){p.closeInventory();return;}buyMachineUpgrade(p,plot,kind,slot==11);return;}
    if(title.equals(OFFERS_MENU)){
      if(slot==49){p.closeInventory();return;}
      List<UUID> rows=offerMenuRows.get(p.getUniqueId());
      if(rows==null||slot>=rows.size())return;
      UUID id=rows.get(slot);SaleOffer offer=saleOffers.get(id);
      if(offer==null||!offer.buyer().equals(p.getUniqueId())||offer.expires()<System.currentTimeMillis()){
        tell(p,"Oferta wygasła lub została anulowana.");openOffers(p);return;
      }
      p.closeInventory();onCommand(p,null,"dzialka",new String[]{"kup",id.toString()});return;
    }
    if(title.equals(SHOP)){if(slot==13){p.closeInventory();buyBlock(p);}else if(slot==22)p.closeInventory();return;}
    if(title.equals(CONFIRM)){if(slot==11&&pendingRemoval.remove(p.getUniqueId()))removeOwnPlot(p);else{pendingRemoval.remove(p.getUniqueId());p.closeInventory();}return;}
    if(title.equals(MAIN)){switch(slot){case 18 -> {p.closeInventory();onCommand(p,null,"dzialka",new String[]{"bank"});}case 20 -> {p.closeInventory();tell(p,"Użyj: /dzialka warp wlacz lub /dzialka warp wylacz");}case 24 -> {p.closeInventory();onCommand(p,null,"dzialka",new String[]{"ranking"});}case 25 -> upgradesMenu(p);case 19 -> sizeMenu(p);case 23 -> challengeMenu(p);case 10 -> {p.closeInventory();shop(p);}case 14 -> membersMenu(p);case 16 -> settingsMenu(p);case 22 -> {p.closeInventory();border(p);}case 26 -> openRemoval(p);}}
    else if(title.equals(MEMBERS)){if(slot==22)menu(p);else if(slot==10||slot==12||slot==14||slot==16){p.closeInventory();tell(p,slot==10?"Użyj: /dzialka zapros <nick>":slot==12?"Użyj: /dzialka zastepca <nick>":slot==14?"Użyj: /dzialka wyrzuc <nick>":"Użyj: /dzialka czlonkowie");}}
    else {if(slot==22){menu(p);return;}if(slot>=FLAGS.length)return;Plot a=current(p);if(a==null||!a.owner.equals(p.getUniqueId()))return;String flag=FLAGS[slot];a.flags.put(flag,!a.flag(flag));save();settingsMenu(p);}
  }
  @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){Player p=e.getPlayer();Block b=e.getBlockPlaced();if(isBlock(e.getItemInHand())){if(owned(p.getUniqueId()).size()>=limit(p)){e.setCancelled(true);tell(p,"Osiągnięto limit działek dla Twojej rangi.");return;}if(b.getType()!=Material.LODESTONE){e.setCancelled(true);return;}if(getConfig().getStringList("zablokowane-swiaty").contains(b.getWorld().getName())){e.setCancelled(true);tell(p,"Na tym świecie działki są wyłączone.");return;}Plot a=new Plot(p.getUniqueId(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ());for(String flag:FLAGS)a.flags.put(flag,getConfig().getBoolean("ustawienia-domyslne."+flag));if(overlaps(a,1)){e.setCancelled(true);tell(p,"Działka nachodziłaby na cudzy teren.");return;}plots.put(a.id,a);selectedPlots.put(p.getUniqueId(),a.id);save();discord("nowa-dzialka","🏡 "+p.getName()+" utworzył działkę "+a.id);Bukkit.getScheduler().runTaskLater(this,()->{spawnDisplay(a);updateHologram(a);},1L);tell(p,msg("dzialka-utworzona","&aUtworzono działkę 32×32! Otwórz /dzialka."));return;}if(!allowed(p,b.getLocation())){e.setCancelled(true);tell(p,"Nie możesz tu budować.");return;}Plot ownerPlot=at(b.getLocation());String kind=machineType(b.getType());if(ownerPlot!=null&&kind!=null&&getConfig().getBoolean("ulepszenia."+kind+".wlaczone",true)){int cap=machineLimit(ownerPlot,kind);if(cap>=0){int current=cachedMachines(ownerPlot,kind,b);if(current<0){e.setCancelled(true);tell(p,"Nie można sprawdzić limitu: część chunków działki jest niezaładowana.");}else if(current>=cap){e.setCancelled(true);tell(p,"Limit "+kind+" na działce: "+cap+". Ulepsz przez /dzialka mechanizmy.");}}}}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void challengePlacement(BlockPlaceEvent e){
    if(!getConfig().getBoolean("wyzwania-tygodniowe.wlaczone",true)||isBlock(e.getItemInHand()))return;
    Plot a=at(e.getBlockPlaced().getLocation());if(a==null||!a.owner.equals(e.getPlayer().getUniqueId()))return;
    refreshChallenge(a);if(a.challengeClaimed||a.challengeBlocks>=challengeTarget())return;
    a.challengeBlocks++;save();
    if(a.challengeBlocks==challengeTarget())tell(e.getPlayer(),"§aWyzwanie ukończone! Odbierz nagrodę: /dzialka wyzwania");
  }
  private boolean allowed(Player p,Location l){Plot a=at(l);return a==null||a.permit(p.getUniqueId(),"budowanie")||admin(p);}
  @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){Plot a=at(e.getBlock().getLocation());if(a!=null&&isAnchor(e.getBlock(),a)){e.setCancelled(true);if(a.owner.equals(e.getPlayer().getUniqueId()))Bukkit.getScheduler().runTask(this,()->openRemoval(e.getPlayer()));else tell(e.getPlayer(),"Tylko właściciel może usunąć działkę.");return;}if(!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getClickedBlock()==null)return;Block b=e.getClickedBlock();Plot a=at(b.getLocation());
    if(e.getAction()==Action.RIGHT_CLICK_BLOCK&&a!=null&&isAnchor(b,a)){
      e.setCancelled(true);if(e.getHand()!=EquipmentSlot.HAND)return;
      if(a.owner.equals(e.getPlayer().getUniqueId())){selectedPlots.put(e.getPlayer().getUniqueId(),a.id);menu(e.getPlayer());}else if(a.can(e.getPlayer().getUniqueId())||admin(e.getPlayer()))menu(e.getPlayer());else tell(e.getPlayer(),"To Blok Działki gracza §e"+name(a.owner)+"§7.");return;
    }
if(a==null||a.permit(e.getPlayer().getUniqueId(),"interakcje")||admin(e.getPlayer()))return;Material m=b.getType();String n=m.name();boolean chest=n.contains("CHEST")||n.contains("BARREL")||n.contains("SHULKER")||n.contains("HOPPER")||n.contains("FURNACE");boolean door=n.contains("DOOR")||n.contains("FENCE_GATE")||n.contains("TRAPDOOR");if((chest&&!a.flag("skrzynie"))||(door&&!a.flag("drzwi"))||(!chest&&!door&&e.getAction()==Action.RIGHT_CLICK_BLOCK&&m.isInteractable()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void bucketEmpty(PlayerBucketEmptyEvent e){if(!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e){if(!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){Player victim=e.getEntity() instanceof Player p?p:null;if(victim==null)return;Player attacker=e.getDamager() instanceof Player p?p:null;if(attacker==null&&e.getDamager() instanceof Projectile proj&&proj.getShooter() instanceof Player p)attacker=p;if(attacker==null)return;Plot a=at(victim.getLocation());if(a!=null&&!a.flag("pvp"))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void spawn(CreatureSpawnEvent e){Plot a=at(e.getLocation());if(a==null)return;EntityType type=e.getEntityType();boolean hostile=e.getEntity() instanceof Monster;if(hostile&&!a.flag("potwory"))e.setCancelled(true);else if(e.getEntity() instanceof Animals&&!a.flag("zwierzeta"))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void entityExplode(EntityExplodeEvent e){e.blockList().removeIf(b->{Plot a=at(b.getLocation());return a!=null&&(isAnchor(b,a)||!a.flag("eksplozje"));});}
  @EventHandler(ignoreCancelled=true) public void blockExplode(BlockExplodeEvent e){e.blockList().removeIf(b->{Plot a=at(b.getLocation());return a!=null&&(isAnchor(b,a)||!a.flag("eksplozje"));});}
  @EventHandler(ignoreCancelled=true) public void fire(BlockSpreadEvent e){Plot a=at(e.getBlock().getLocation());if(a!=null&&!a.flag("ogien")&&e.getSource().getType()==Material.FIRE)e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void burn(BlockBurnEvent e){Plot a=at(e.getBlock().getLocation());if(a!=null&&!a.flag("ogien"))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){if(e.getTo()==null||e.getFrom().getBlockX()==e.getTo().getBlockX()&&e.getFrom().getBlockZ()==e.getTo().getBlockZ()&&e.getFrom().getWorld().equals(e.getTo().getWorld()))return;Player p=e.getPlayer();Plot from=at(e.getFrom()),to=at(e.getTo());if(to!=null&&!to.flag("wejscie")&&!to.can(p.getUniqueId())&&!admin(p)){e.setTo(e.getFrom());return;}UUID old=from==null?null:from.owner,now=to==null?null:to.owner;if(Objects.equals(old,now))return;if(to!=null)p.sendTitle("§aDziałka gracza","§e"+name(to.owner),5,45,10);else if(from!=null)p.sendTitle("§6Opuszczasz działkę","§e"+name(from.owner),5,45,10);
    if(to!=null&&to.flag("noc")){p.setPlayerTime(18000,false);shownNight.put(p.getUniqueId(),to.owner);}else if(shownNight.remove(p.getUniqueId())!=null)p.resetPlayerTime();
    // Latanie wymaga oddzielnego bezpiecznego systemu przyznawania; flaga jest zachowana, lecz nie daje automatycznie fly.
  }
  @EventHandler(ignoreCancelled=true) public void protectDisplay(org.bukkit.event.entity.EntityDamageEvent e){if(e.getEntity() instanceof ItemDisplay d&&(d.getPersistentDataContainer().has(displayKey,PersistentDataType.STRING)||d.getPersistentDataContainer().has(legacyDisplayKey,PersistentDataType.STRING)))e.setCancelled(true);}
  @EventHandler public void closeGui(org.bukkit.event.inventory.InventoryCloseEvent e){
    if(e.getView().getTitle().equals(CONFIRM))pendingRemoval.remove(e.getPlayer().getUniqueId());
  }
  @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();BukkitTask t=borders.remove(id);if(t!=null)t.cancel();shownNight.remove(id);pendingRemoval.remove(id);pendingSize.remove(id);pendingUpgrade.remove(id);pendingUpgradePlot.remove(id);offerMenuRows.remove(id);}
  private void load(){
    plots.clear();
    dataLoadFailed=false;
    YamlConfiguration y=YamlConfiguration.loadConfiguration(file);
    ConfigurationSection section=y.getConfigurationSection("dzialki");
    if(section==null)return;
    for(String key:section.getKeys(false)){
      try{
        String b="dzialki."+key;
        UUID id=UUID.fromString(key);
        UUID owner=UUID.fromString(y.getString(b+".wlasciciel",key));
        String world=y.getString(b+".swiat");
        if(world==null||world.isBlank())throw new IllegalArgumentException("Brak świata");
        Plot a=new Plot(owner,world,y.getInt(b+".x"),y.getInt(b+".y"),y.getInt(b+".z"));
        a.id=id;a.size=y.getInt(b+".rozmiar",32);if(a.size<32||a.size>250)throw new IllegalArgumentException("Niepoprawny rozmiar działki");
        String group=y.getString(b+".grupa");if(group!=null&&!group.isBlank())a.groupId=UUID.fromString(group);
        
        for(String v:y.getStringList(b+".czlonkowie"))a.members.add(UUID.fromString(v));
        for(String v:y.getStringList(b+".zastepcy"))a.deputies.add(UUID.fromString(v));
        ConfigurationSection ps=y.getConfigurationSection(b+".uprawnienia-czlonkow");
        if(ps==null){for(UUID member:a.members)a.memberPermissions.put(member,new HashSet<>(Arrays.asList("budowanie","interakcje")));}
        if(ps!=null)for(String uid:ps.getKeys(false)){
          UUID member=UUID.fromString(uid);
          if(!a.members.contains(member))continue;
          Set<String> perms=new HashSet<>();
          for(String action:ps.getStringList(uid))if(action.equals("budowanie")||action.equals("interakcje"))perms.add(action);
          a.memberPermissions.put(member,perms);
        }
        for(String f:FLAGS)a.flags.put(f,y.getBoolean(b+".ustawienia."+f,getConfig().getBoolean("ustawienia-domyslne."+f)));
        for(String kind:MACHINES)a.machineLevels.put(kind,Math.max(1,y.getInt(b+".mechanizmy."+kind,1)));
        a.challengeWeek=y.getString(b+".wyzwanie.tydzien","");a.challengeBlocks=Math.max(0,y.getInt(b+".wyzwanie.bloki",0));a.challengeClaimed=y.getBoolean(b+".wyzwanie.odebrane",false);
        a.title=y.getString(b+".nazwa","");a.description=y.getString(b+".opis","");a.bank=Math.max(0,y.getDouble(b+".bank",0));a.publicWarp=y.getBoolean(b+".publiczny-warp",false);for(String idLike:y.getStringList(b+".polubienia"))a.likes.add(UUID.fromString(idLike));
        if(overlaps(a,1))throw new IllegalArgumentException("Kolizja z inną działką");
        plots.put(a.id,a);
      }catch(Exception ex){dataLoadFailed=true;getLogger().severe("Nie można wczytać działki "+key+": "+ex.getMessage()+". Zapis zablokowany.");getServer().getPluginManager().disablePlugin(this);return;}
    }
  }
  private void save(){
    if(file==null||dataLoadFailed)return;
    YamlConfiguration y=new YamlConfiguration();
    y.set("format",2);
    for(Plot a:plots.values()){
      String b="dzialki."+a.id;
      y.set(b+".wlasciciel",a.owner.toString());y.set(b+".grupa",a.groupId==null?null:a.groupId.toString());
      y.set(b+".swiat",a.world);y.set(b+".x",a.x);y.set(b+".y",a.y);y.set(b+".z",a.z);
      y.set(b+".blok-jako-srodek",true);y.set(b+".rozmiar",a.size);
      y.set(b+".czlonkowie",a.members.stream().map(UUID::toString).toList());
      y.set(b+".zastepcy",a.deputies.stream().map(UUID::toString).toList());
      for(var entry:a.memberPermissions.entrySet())if(a.members.contains(entry.getKey()))y.set(b+".uprawnienia-czlonkow."+entry.getKey(),new ArrayList<>(entry.getValue()));
      for(String f:FLAGS)y.set(b+".ustawienia."+f,a.flag(f));
      for(String kind:MACHINES)y.set(b+".mechanizmy."+kind,a.machineLevels.getOrDefault(kind,1));
      y.set(b+".wyzwanie.tydzien",a.challengeWeek);y.set(b+".wyzwanie.bloki",a.challengeBlocks);y.set(b+".wyzwanie.odebrane",a.challengeClaimed);
      y.set(b+".nazwa",a.title);y.set(b+".opis",a.description);y.set(b+".bank",a.bank);y.set(b+".publiczny-warp",a.publicWarp);y.set(b+".polubienia",a.likes.stream().map(UUID::toString).toList());
    }
    try{
      File temp=new File(getDataFolder(),"dzialki.yml.tmp");y.save(temp);
      try{java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
      catch(java.nio.file.AtomicMoveNotSupportedException ex){java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }catch(IOException ex){getLogger().severe("Błąd zapisu działek: "+ex.getMessage());}
  }
}
