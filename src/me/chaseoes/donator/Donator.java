package me.chaseoes.donator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

public class Donator extends JavaPlugin {
	/* Please remember that this plugin is still highly beta!
	 * If you would like to contribute please submit a pull request, thanks!
	 */
	
	// MySQL Connection Information
	Connection conn;
	String user;
	String pass;
	String host;
	String database;
	Integer port;
	String url;

	// Other Variables
	public final Logger log = Logger.getLogger("Minecraft");
	
	public void setupMysql() {
		try {
			Statement st = conn.createStatement();
			  String table = 
			"CREATE TABLE IF NOT EXISTS players(id INT NOT NULL AUTO_INCREMENT, PRIMARY KEY(id), username TEXT(20), amount TEXT(5), date TEXT, processed TEXT)";
			  st.executeUpdate(table);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}
	
	public ResultSet getNewDonors() {
		ResultSet result = null;
		try {
			Statement statement;
			statement = conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
			result = statement.executeQuery("SELECT * FROM players WHERE processed='false'");
			return result;
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	public void onEnable() {
		// Load Configuration
		try {
			getConfig().options().copyDefaults(true);
			saveConfig();
		} catch (Exception ex) {
			getLogger().log(Level.SEVERE, "[Donator] Could not load configuration!", ex);
		}

		user = getConfig().getString("database.username");
		pass = getConfig().getString("database.password");
		host = getConfig().getString("database.hostname");
		database = getConfig().getString("database.database_name");
		port = getConfig().getInt("database.port");
		url = "jdbc:mysql://" + host + ":" + port + "/" + database;
		
		// Connect to MySQL Database
		try {
			conn = DriverManager.getConnection(url, user, pass);
		} catch (final Exception e) {
			getLogger().log(Level.SEVERE, "[Donator] Could not connect to database! Verify your database details in the configuration are correct.", e);
			getServer().getPluginManager().disablePlugin(this);
		}

		setupMysql();

		// Start a task to check for new donations.
		getServer().getScheduler().scheduleAsyncRepeatingTask(this, new Runnable() {
			public void run() {
				ResultSet r = getNewDonors();
				try {
					while (r.next()) {
						String user = r.getString("username");
						String amount = r.getString("amount");
						if (getServer().getPlayer(user) != null) { // If the user is offline then leave them in the queue.
							getServer().broadcastMessage("§aPlease thank " + user + " for donating $" + amount + "! :D");
							for (String pack : getConfig().getConfigurationSection("packages").getKeys(false)) {
								String price = getConfig().getString("packages." + pack + ".price");
								List<String> commands = getConfig().getStringList("packages." + pack + ".commands");
								try {
									System.out.println(getExpiresDate(getCurrentDate(), pack));
								} catch (ParseException e) {
									e.printStackTrace();
								}
								if (amount.equals(price)) {
									for (String cmnd : commands) {
										getServer().dispatchCommand(getServer().getConsoleSender(), cmnd.replace("%player", getServer().getPlayer(user).getName()));
									}
								}
							}
							r.updateString("processed", "true"); // Remove the user from the queue.
							r.updateRow();
						}
					}
				} catch (SQLException e) {
					System.out.println("[SEVERE] [Donator] Error while trying to connect to the MySQL database when checking for new donations!");
				}
			}
		}, 200L, getConfig().getInt("settings.checkdelay") * 20);

		log.info("[Donator] Version " + getDescription().getVersion() + " by chaseoes" + " has been enabled!");
	}

	public void onDisable() {
		reloadConfig();
		saveConfig();
		try {
		} catch (final Exception e) {
			getLogger().log(Level.SEVERE, "[Donator] MYSQL failed!", e);
		}
		getServer().getScheduler().cancelTasks(this);
		log.info("[Donator] Version " + getDescription().getVersion() + " by chaseoes" + " has been disabled!");
	}

	public String getCurrentDate() {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = new Date();
		return dateFormat.format(date);
	}

	public String getExpiresDate(String currentdate, String packagename) throws ParseException {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		Calendar c = Calendar.getInstance();
		c.setTime(sdf.parse(currentdate));
		c.add(Calendar.DATE, getConfig().getInt("packages." + packagename + ".expires"));  // number of days to add
		return sdf.format(c.getTime());  // dt is now the new date
	}
	
	// Can anyone figure out a good way to detect when the package should expire? :)

//	public void Blargh() {
//		ResultSet result = null;
//		try {
//			Statement statement;
//			statement = conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
//			result = statement.executeQuery("SELECT * FROM players WHERE expired='false'");
//		} catch (final Exception e) {
//			e.printStackTrace();
//		}
//		try {
//			while (result.next()) {
//				String date = result.getString("expiresdate");
//				String amount = result.getString("amount");
//				if (date.equalsIgnoreCase(getCurrentDate())) {
//					for (String pack : getConfig().getConfigurationSection("packages").getKeys(false)) {
//						List<String> commands = getConfig().getStringList("packages." + pack + ".expirescommands");
//						String price = getConfig().getString("packages." + pack + ".price");
//						if (amount.equals(price)) {
//							for (String cmnd : commands) {
//								getServer().dispatchCommand(getServer().getConsoleSender(), cmnd);
//							}
//						}
//					}
//				}
//			}
//		} catch (CommandException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		} catch (SQLException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//	}
}

