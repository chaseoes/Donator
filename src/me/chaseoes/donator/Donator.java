package me.chaseoes.donator;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class Donator extends JavaPlugin {

	// MySQL Connection Information
	Connection conn;
	String user, pass, url;
	Integer port;
	Boolean connectionfailed = true;
	Boolean debuggling = false;

	// Other Variables
	public final Logger log = Logger.getLogger("Minecraft");
	public static HashSet<String> settingwall = new HashSet<String>();

	public void onEnable() {
		// Generate Default Configuration
		File configFile = new File(getDataFolder(), "config.yml");
		if (!configFile.exists()) {
			List<String> defaultcommands = new ArrayList<String>();
			List<String> defaultexpirescommands = new ArrayList<String>();
			List<String> signwall = new ArrayList<String>();
			defaultcommands.add("promote %player");
			defaultcommands.add("msg %player Thanks for donating %amount!");
			defaultexpirescommands.add("demote %player");
			signwall.add("This is line 1!");
			signwall.add("%player");
			signwall.add("%amount");
			getConfig().addDefault("packages.example.price", "5.00");
			getConfig().addDefault("packages.example.expires", "0");
			getConfig().addDefault("packages.example.expires", "0");
			getConfig().addDefault("signwall-format", signwall);
			getConfig().addDefault("packages.example.commands", defaultcommands);
			getConfig().addDefault("packages.example.expirescommands", defaultexpirescommands);
		}

		// Load Configuration
		getConfig().options().copyDefaults(true);
		saveConfig();
		debuggling = getConfig().getBoolean("settings.debug");

		// Connect to MySQL Database
		user = getConfig().getString("database.username");
		pass = getConfig().getString("database.password");
		url = "jdbc:mysql://" + getConfig().getString("database.hostname") + ":" + getConfig().getInt("database.port") + "/" + getConfig().getString("database.database_name");

		try {
			conn = DriverManager.getConnection(url, user, pass);
			connectionfailed = false;
		} catch (final Exception e) {
			getLogger().log(Level.SEVERE, "Could not connect to database! Verify your database details in the configuration are correct.");
			if (debuggling) e.printStackTrace();
			getServer().getPluginManager().disablePlugin(this);
		}

		setupMysql();

		if (!connectionfailed) {
			// Register Events
			getServer().getPluginManager().registerEvents(new DonatorListeners(this), this);
			getServer().getPluginManager().registerEvents(new DonateEventListener(this), this);

			// Start a task to check for new donations.
			getServer().getScheduler().scheduleAsyncRepeatingTask(this, new Runnable() {
				public void run() {
					checkForDonations();
				}
			}, 200L, getConfig().getInt("settings.checkdelay") * 20);
		}
	}

	public void onDisable() {
		// Save Configuration
		reloadConfig();
		saveConfig();

		// Cancel Tasks
		getServer().getScheduler().cancelTasks(this);
	}

	public void checkForDonations() {
		if (!connectionfailed) {

			// Define Variables
			ResultSet r = getNewDonors();
			ResultSet rx = getResultSet("SELECT * FROM donations WHERE expired='false'");

			// Check for and process new donations.
			try {
				while (r.next()) {
					String user = r.getString("username");
					Double amount = r.getDouble("amount");
					DonateEvent event = new DonateEvent(r.getString("username"), r.getDouble("amount"), r.getString("date"), r.getString("first_name"), r.getString("last_name"), r.getString("payer_email"), r.getString("expires"));
					getServer().getPluginManager().callEvent(event);
					r.updateString("processed", "true");
					r.updateRow();
					updateDonorPlayers(user, getTotalDonated(user) + amount);
				}
			} catch (Exception e) {
				log.severe("[Donator] Error encountered when checking for new donations!");
				if (debuggling) e.printStackTrace();
			}

			// Check for and process expired donations.
			try {
				while (rx.next()) {
					if (rx.getString("expires") != null && rx.getString("expires").equalsIgnoreCase(getCurrentDate())) {
						String user = rx.getString("username");
						Double amount = rx.getDouble("amount");
						for (String pack : getConfig().getConfigurationSection("packages").getKeys(false)) {
							String price = getConfig().getString("packages." + pack + ".price");
							List<String> commands = getConfig().getStringList("packages." + pack + ".expires-commands");
							if (!getConfig().getBoolean("settings.cumulativepackages")) {
								if (amount.equals(price) || (amount + "0").equals(price)) {
									for (String cmnd : commands) {
										getServer().dispatchCommand(getServer().getConsoleSender(), cmnd.replace("%player", user).replace("%amount", amount + ""));
									}
								}
							} else {
								Double total = getTotalDonated(user);
								if (total.equals(price) || (total + "0").equals(price)) {
									for (String cmnd : commands) {
										getServer().dispatchCommand(getServer().getConsoleSender(), cmnd.replace("%player", user).replace("%amount", amount + ""));
									}
								}
							}
						}
						rx.updateString("expired", "true");
						rx.updateRow();
					}
				}
			} catch (Exception e) {
				log.severe("[Donator] Error encountered when checking for expired donations!");
				if (debuggling) e.printStackTrace();
			}
		}
	}

	// Connect to MySQL and create tables.
	public void setupMysql() {
		if (!connectionfailed) {
			try {
				Statement st = conn.createStatement();
				String table = "CREATE TABLE IF NOT EXISTS donations(id INT NOT NULL AUTO_INCREMENT, PRIMARY KEY(id), username TEXT(20), amount TEXT(5), date TEXT, processed TEXT, sandbox TEXT, first_name TEXT, last_name TEXT, payer_email TEXT, expires TEXT, expired TEXT)";
				String ptable = "CREATE TABLE IF NOT EXISTS players(username TEXT(20), amount TEXT(5))";
				st.executeUpdate(table);
				st.executeUpdate(ptable);
			} catch (final Exception e) {
				if (debuggling) e.printStackTrace();
			}
		}
	}

	public ResultSet getNewDonors() {
		return getResultSet("SELECT * FROM donations WHERE processed='false'");
	}

	public ResultSet getSetToExpireDonors() {
		return getResultSet("SELECT * FROM donations WHERE expired='false'");
	}

	public ResultSet getRecentDonors() {
		return getResultSet("SELECT * FROM donations ORDER BY id DESC LIMIT 5");
	}

	public ResultSet getDonationResult(String id) {
		return getResultSet("SELECT * FROM donations WHERE id='" + id +"'");
	}

	public Integer getDupes(String username, String amount) {
		ResultSet r = getResultSet("SELECT * FROM donations WHERE username = '" + username + "' AND amount = '" + amount + "'");
		Integer donationcount = 0;
		try {
			while (r.next()) {
				donationcount++;
			}
		} catch (SQLException e) {
			if (debuggling) e.printStackTrace();
		}
		return donationcount;
	}

	public void updateDonorPlayers(String user, Double amount) {
		try {
			Statement st = conn.createStatement();
			String sta = "REPLACE INTO players SET username = '" + user + "', amount = '" + amount +"'";
			st.executeUpdate(sta);
		} catch (SQLException e) {
			if (debuggling) e.printStackTrace();
		}
	}

	public String parseAmount(Double amount) {
		if (amount.toString().endsWith(".0")) {
			return "$" + amount + "0";
		}
		return "$" + amount;
	}

	public String parseAmount(String amount) {
		if (amount.toString().endsWith(".0")) {
			return "$" + amount + "0";
		}
		return "$" + amount;
	}

	// Calculate the total amount a player has donated.
	public Double getTotalDonated(String player) {
		ResultSet r = getResultSet("SELECT * FROM players WHERE username='" + player + "'");
		Double am = null;
		try {
			while (r.next()) {
				am = r.getDouble("amount");
			}
		} catch (SQLException e) {
			if (debuggling) e.printStackTrace();
		}
		if (am != null) {
			return am;
		} else {
			return Double.parseDouble("0");
		}
	}

	// General method to get a result set.
	public ResultSet getResultSet(String statement) {
		ResultSet result = null;
		try {
			Statement st;
			st = conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
			if (!getConfig().getBoolean("settings.sandbox")) {
				return st.executeQuery(statement);
			} else {
				return st.executeQuery(statement);
			}
		} catch (SQLException e) {
			if (debuggling) e.printStackTrace();
		}
		return result;
	}

	// Calculate the expires date for a package.
	public String getExpiresDate(String packagename) {
		Integer days = getConfig().getInt("packages." + packagename + ".expires");
		if (!(days == 0)) {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
			Calendar c = Calendar.getInstance();
			try {
				c.setTime(sdf.parse(getCurrentDate()));
			} catch (ParseException e) {
				if (debuggling) e.printStackTrace();
			}
			c.add(Calendar.DATE, days);
			String exp = sdf.format(c.getTime());
			return exp;
		}
		return null;
	}

	public void updateSignWall(String use, Double amount) {
		Location loc1 = new Location(getServer().getWorld(getConfig().getString("signwall.1.w")), getConfig().getInt("signwall.1.x"), getConfig().getInt("signwall.1.y"), getConfig().getInt("signwall.1.z"));
		Location loc2 = new Location(getServer().getWorld(getConfig().getString("signwall.2.w")), getConfig().getInt("signwall.2.x"), getConfig().getInt("signwall.2.y"), getConfig().getInt("signwall.2.z"));
		Integer x1 = loc1.getBlockX(), y1 = loc1.getBlockY(), z1 = loc1.getBlockZ();
		Integer x2 = loc2.getBlockX(), y2 = loc2.getBlockY(), z2 = loc2.getBlockZ();
		Integer minx = Math.min(x1, x2);
		Integer minz = Math.min(z1, z2);
		Integer miny = Math.min(y1, y2);
		Integer maxx = Math.max(x1, x2);
		Integer maxy = Math.max(y1, y2), maxz = Math.max(z1, z2);
		List<String> li = getConfig().getStringList("signwall-format");
		for (Integer x = minx; x <= maxx; x++) for (Integer y = miny; y <= maxy; y++) for (Integer z = minz; z <= maxz; z++) {
			Block b = getServer().getWorld(getConfig().getString("signwall.2.w")).getBlockAt(x, y, z);
			Sign sign = (Sign)b.getState();
			if (sign.getLine(0).isEmpty() || sign.getLine(0) == null) {
				Integer currentline = -1;
				for (String line : li) {
					if (currentline < 5) {
						currentline++;
						sign.setLine(currentline, line.replace("%player", use).replace("%amount", parseAmount(amount)));
					}
				}
				sign.update();
				currentline = -1;
				return;
			}
		}
		return;
	}

	public String getCurrentDate() {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = new Date();
		return dateFormat.format(date);
	}

	public String colorize(String s){
		if(s == null) return null;
		return s.replaceAll("&([l-o0-9a-f])", "\u00A7$1");
	}

	public void formatRecentQuick(Player player, String id, String date, String username, String amount, String totalamount) {
		player.sendMessage("§8(#" + id + ") §6" + date.substring(9, date.length()).substring(0, 12) + " §a" + username + " §6for §a$" + amount + " §c(" + parseAmount(totalamount) + " Total)");
	}

	public void formatRecentByPlayer(Player player, String id, String date, String amount) {
		player.sendMessage("§8(#" + id + ") §6" + date.substring(9, date.length()).substring(0, 12) + " §6for §a$" + amount + "§6.");
	}

	public void noPermission(CommandSender cs) {
		cs.sendMessage("§8[Donator] §cYou do not have permission to do that.");
	}

	@Override
	public boolean onCommand(CommandSender cs, Command cmnd, String string, String[] strings) {
		if (cmnd.getName().equalsIgnoreCase("donator")) {
			if (strings.length == 0) {
				cs.sendMessage("§8-------------- §6Donator §8- §aGeneral Info. §8--------------");
				cs.sendMessage("§7Accept donations and automatically give ingame perks!");
				cs.sendMessage("§7Plugin developed by §9chaseoes§7.");
				cs.sendMessage("§6http://dev.bukkit.org/server-mods/donator/");
			}
			if (strings.length == 1) {
				if (strings[0].equalsIgnoreCase("recent")) {
					if (cs.hasPermission("donator.recent")) {
						cs.sendMessage("§8-------------- §6Donator §8- §aRecent Donations §8--------------");
						ResultSet r = getRecentDonors();
						try {
							while (r.next()) {
								formatRecentQuick((Player) cs, r.getString("id"), r.getString("date"), r.getString("username"), r.getString("amount"), getTotalDonated(r.getString("username")) + "");
							}
						} catch (SQLException e) {
							if (debuggling) e.printStackTrace();
						}
					} else {
						noPermission(cs);
					}
				}
				if (strings[0].equalsIgnoreCase("setwall")) {
					if (cs instanceof Player) {
						if (cs.hasPermission("donator.setwall")) {
							if (!settingwall.contains(cs.getName())) {
								settingwall.add(cs.getName());
								cs.sendMessage("§8-------------- §6Donator §8- §aSign Wall §8--------------");
								cs.sendMessage("§8Wand enabled, please use a golden axe to set the sign wall.");
							} else {
								settingwall.remove(cs.getName());
								cs.sendMessage("§8-------------- §6Donator §8- §aSign Wall §8--------------");
								cs.sendMessage("§8Wand disabled.");
							}
						} else {
							noPermission(cs);
						}
					} else {
						cs.sendMessage("You can only do that as a player!");
					}
				}
				if (strings[0].equalsIgnoreCase("reload")) {
					if (cs.hasPermission("donator.reload")) {
						reloadConfig();
						saveConfig();
						cs.sendMessage("§8-------------- §6Donator §8- §aConfiguration §8--------------");
						cs.sendMessage("§8Successfully reloaded the configuration.");
					} else {
						noPermission(cs);
					}
				}
			}
			if (strings.length == 2) {
				if (strings[0].equalsIgnoreCase("check")) {
					if (cs.hasPermission("donator.check")) {
						ResultSet r = getDonationResult(strings[1]);
						cs.sendMessage("§8-------------- §6Donator §8- §aDonation # " + strings[1] + " §8--------------");
						try {
							while (r.next()) {
								cs.sendMessage("§6Player Name: §c" + r.getString("username"));
								cs.sendMessage("§6Amount Donated: §a$" + r.getString("amount"));
								cs.sendMessage("§6Date Donated: §7" + r.getString("date").substring(9, r.getString("date").length()).substring(0, 12));
								cs.sendMessage("§6Full Name: §7" + r.getString("first_name") + " " + r.getString("last_name"));
								cs.sendMessage("§6Email: §7" + r.getString("payer_email"));
								cs.sendMessage("§6Has Expired: §7" + r.getString("expired").replace("false", "No").replace("true", "Yes"));
								cs.sendMessage("§6Expires On: §7" + r.getString("expires").replace("null", "Never"));
							}
						} catch (SQLException e) {
							if (debuggling) e.printStackTrace();
						}
					} else {
						noPermission(cs);
					}
				}
				if (strings[0].equalsIgnoreCase("checkplayer")) {
					if (cs.hasPermission("donator.check")) {
						cs.sendMessage("§8-------------- §6Donator §8- §aPlayer " + strings[1] + " §8--------------");
						cs.sendMessage("§6Player: §c" + strings[1]);
						cs.sendMessage("§6Total Donated: §a$" + getTotalDonated(strings[1]));
						ResultSet r = getResultSet("SELECT * FROM donations WHERE username='" + strings[1] + "' ORDER BY id DESC LIMIT 5");
						try {
							while (r.next()) {
								formatRecentByPlayer((Player) cs, r.getString("id"), r.getString("date"), r.getString("amount"));
							}
						} catch (SQLException e) {
							if (debuggling) e.printStackTrace();
						}
					} else {
						noPermission(cs);
					}
				}
			}
		}
		return true;
	}
}
