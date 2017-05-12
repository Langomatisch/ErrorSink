package me.wiefferink.errorsink;

import com.getsentry.raven.Raven;
import com.getsentry.raven.RavenFactory;
import com.getsentry.raven.dsn.InvalidDsnException;
import me.wiefferink.errorsink.editors.Breadcrumbs;
import me.wiefferink.errorsink.editors.PluginInformation;
import me.wiefferink.errorsink.editors.RuleData;
import me.wiefferink.errorsink.editors.StackInformation;
import me.wiefferink.errorsink.filters.ErrorSinkFilter;
import me.wiefferink.errorsink.filters.RuleFilter;
import me.wiefferink.errorsink.tools.Analytics;
import me.wiefferink.errorsink.tools.Log;
import me.wiefferink.errorsink.tools.Utils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO try changing System.err logging to level error instead of warn?
// TODO command to reload config?
public class ErrorSink extends JavaPlugin {

	private static ErrorSink instance;
	private Raven raven;
	private BukkitSentryAppender appender;
	private Map<String, EventRuleMatcher> matcherMap;
	private int messagesSent = 0;
	private BukkitRavenFactory bukkitRavenFactory;

	/**
	 * Constructor
	 * Collection is already enabled here instead of onEnable to also capture
	 * loading bugs in other plugins (like missing dependencies)
	 */
	public ErrorSink() {
		instance = this;
		Log.setLogger(getLogger());
		Log.setDebug(getConfig().getBoolean("debug"));
		saveDefaultConfig();

		bukkitRavenFactory = new BukkitRavenFactory();
		// Add factory, normally done automatically but we relocated the Sentry classes
		RavenFactory.registerFactory(bukkitRavenFactory);

		String dsn = getConfig().getString("dsn");
		if(dsn == null || dsn.isEmpty()) {
			Log.error("Provide a DSN from Sentry.io in the config to get started!");
			return;
		}

		List<String> matchSectionNames = Arrays.asList("events.filters", "events.rules", "breadcrumbs.filters", "breadcrumbs.rules");
		matcherMap = new HashMap<>();
		for(String matchSectionName : matchSectionNames) {
			ConfigurationSection matchSection = getConfig().getConfigurationSection(matchSectionName);
			if(matchSection != null) {
				for(String eventRuleKey : matchSection.getKeys(false)) {
					matcherMap.put(matchSection.getCurrentPath() + "." + eventRuleKey, new EventRuleMatcher(matchSection.getConfigurationSection(eventRuleKey)));
				}
			}
		}

		startCollecting(dsn);
	}

	@Override
	public void onEnable() {
		ErrorSink.getInstance().getServer().getPluginManager().registerEvents(bukkitRavenFactory, ErrorSink.getInstance());
		// All plugins loaded now, update packages
		bukkitRavenFactory.updateInAppFrames();
		// Services should now be registered
		Utils.run(bukkitRavenFactory::updateInAppFrames);
		// Just to be sure
		Utils.run(20L, bukkitRavenFactory::updateInAppFrames);

		Analytics.start();
	}

	@Override
	public void onDisable() {
		// Remove and shutdown appender to prevent double appenders because of disable/enable
		Logger logger = (Logger)LogManager.getRootLogger();
		if(appender != null) {
			logger.removeAppender(appender);
			appender.shutdown();
		}

		HandlerList.unregisterAll(this);
	}

	/**
	 * Get the currenlty active instance of ErrorSink
	 * @return ErrorSink instance
	 */
	public static ErrorSink getInstance() {
		return instance;
	}

	/**
	 * Add a message sent
	 */
	public void increaseMessageSent() {
		messagesSent++;
	}

	/**
	 * Get the message count sent since last reset
	 *
	 * @return Messages sent since last reset
	 */
	public int getAndResetMessageSent() {
		int result = messagesSent;
		messagesSent = 0;
		return result;
	}

	/**
	 * Start collecting events
	 * @param dsn The Sentry DSN to use to send the events
	 */
	public void startCollecting(String dsn) {
		// Setup connection to Sentry.io
		try {
			raven = RavenFactory.ravenInstance(dsn);
		} catch(InvalidDsnException | IllegalArgumentException e) {
			Log.error("Provided Sentry DSN is invalid:", ExceptionUtils.getStackTrace(e));
			return;
		}

		Logger logger = (Logger)LogManager.getRootLogger();

		// Start collecting errors from the Logger
		appender = new BukkitSentryAppender(raven);

		// Filters
		appender.addFilter(new ErrorSinkFilter());
		appender.addFilter(new RuleFilter());

		// Editors
		appender.addEventEditor(new RuleData());
		appender.addEventEditor(new StackInformation());
		appender.addEventEditor(new PluginInformation());

		// Default data
		appender.setServerName(getServerName());
		appender.setRelease(getRelease());

		// Start the collector
		appender.start();
		logger.addAppender(appender);

		// Add later to prevent reported messages also appearing in the breadcrumbs
		appender.addEventEditor(new Breadcrumbs(logger));
	}

	/**
	 * Get the server name that should be used
	 * @return The server name
	 */
	public String getServerName() {
		String serverName = getConfig().getString("serverName");
		if(serverName == null || serverName.isEmpty()) {
			serverName = Bukkit.getServerName();
		}

		// Server name can never be null/empty, this will cause Raven to lookup the hostname and kills the server somehow
		if (serverName == null || serverName.isEmpty()) {
			serverName = "ServerName";
		}
		return serverName;
	}

	/**
	 * Get the release to use
	 * @return The release (version of the ErrorSink plugin)
	 */
	public String getRelease() {
		return getDescription().getVersion();
	}

	/**
	 * Get the Raven instance, for example for adding extra BuilderHelpers
	 * @return The used Raven instance
	 */
	public Raven getRaven() {
		return raven;
	}

	/**
	 * Match a rule to an event
	 *
	 * @param matcherPath The path of the matcher rules to use
	 * @param message     The message to match
	 * @param level       The level to match
	 * @param throwable   The exception to match
	 * @param threadName  The thread name to match
	 * @param loggerName  The logger name to match
	 * @return A map with the captured groups if a match is found, otherwise null
	 */
	public Map<String, String> match(String matcherPath, String message, Level level, Throwable throwable, String threadName, String loggerName) {
		EventRuleMatcher matcher = matcherMap.get(matcherPath);
		if(matcher == null) {
			Log.error("Trying to match path", matcherPath, "but there is no EventRuleMatcher!");
			return null;
		}

		return matcher.matches(message, level, throwable, threadName, loggerName);
	}


}
