package io.github.devoracode.feignauth.feign;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * 测试用的 Logback appender 挂载工具，用于断言日志级别的降级行为。
 */
public final class LogCaptures {

	private final Logger logger;

	private final ListAppender<ILoggingEvent> events = new ListAppender<>();

	private LogCaptures(Logger logger) {
		this.logger = logger;
	}

	public static LogCaptures attach(Class<?> owner) {
		Logger logger = (Logger) LoggerFactory.getLogger(owner.getName());
		logger.setLevel(Level.DEBUG);
		LogCaptures captures = new LogCaptures(logger);
		captures.events.start();
		logger.addAppender(captures.events);
		return captures;
	}

	public void detach() {
		this.logger.detachAppender(this.events);
	}

	public long count(Level level) {
		return this.events.list.stream().filter(event -> level.equals(event.getLevel())).count();
	}

	public boolean contains(Level level, String substring) {
		return this.events.list.stream()
				.filter(event -> level.equals(event.getLevel()))
				.anyMatch(event -> event.getFormattedMessage().contains(substring));
	}

}
