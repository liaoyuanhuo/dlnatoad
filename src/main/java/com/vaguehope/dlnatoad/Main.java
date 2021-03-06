package com.vaguehope.dlnatoad;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teleal.cling.UpnpService;
import org.teleal.cling.UpnpServiceImpl;
import org.teleal.cling.model.meta.Icon;
import org.teleal.cling.model.resource.IconResource;
import org.teleal.cling.model.resource.Resource;
import org.teleal.cling.protocol.ProtocolFactory;
import org.teleal.cling.registry.Registry;

import com.sun.akuma.Daemon;
import com.vaguehope.dlnatoad.dlnaserver.ContentServlet;
import com.vaguehope.dlnatoad.dlnaserver.ContentTree;
import com.vaguehope.dlnatoad.dlnaserver.MediaServer;
import com.vaguehope.dlnatoad.dlnaserver.RegistryImplWithOverrides;
import com.vaguehope.dlnatoad.media.MediaFormat;
import com.vaguehope.dlnatoad.media.MediaIndex;
import com.vaguehope.dlnatoad.media.MediaIndex.HierarchyMode;
import com.vaguehope.dlnatoad.ui.IndexServlet;
import com.vaguehope.dlnatoad.util.LogHelper;
import com.vaguehope.dlnatoad.util.NetHelper;
import com.vaguehope.dlnatoad.util.Watcher;

public final class Main {

	protected static final Logger LOG = LoggerFactory.getLogger(Main.class);

	private Main () {
		throw new AssertionError();
	}

	public static void main (final String[] rawArgs) throws Exception { // NOSONAR
		LogHelper.bridgeJul();

		final PrintStream err = System.err;
		final Args args = new Args();
		final CmdLineParser parser = new CmdLineParser(args);
		try {
			parser.parseArgument(rawArgs);
			daemonise(args);
			run(args);
		}
		catch (final CmdLineException e) {
			err.println(e.getMessage());
			help(parser, err);
			return;
		}
		catch (final Exception e) {
			err.println("An unhandled error occured.");
			e.printStackTrace(err);
		}
	}

	private static void daemonise (final Args args) throws Exception {
		final Daemon d = new Daemon.WithoutChdir();
		if (d.isDaemonized()) {
			d.init(null); // No PID file for now.
		}
		else if (args.isDaemonise()) {
			d.daemonize();
			LOG.info("Daemon started.");
			System.exit(0);
		}
	}

	private static void run (final Args args) throws Exception { // NOSONAR
		final String hostName = InetAddress.getLocalHost().getHostName();
		final List<InetAddress> addresses = NetHelper.getIpAddresses();
		final InetAddress address = addresses.iterator().next();
		LOG.info("hostName: {}", hostName);
		LOG.info("addresses: {} using address: {}", addresses, address);

		final UpnpService upnpService = makeUpnpServer();
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run () {
				upnpService.shutdown();
			}
		});

		final ContentTree contentTree = new ContentTree();
		upnpService.getRegistry().addDevice(new MediaServer(contentTree, hostName).getDevice());
		final Server server = startContentServer(contentTree);

		final String externalHttpContext = "http://" + address.getHostAddress() + ":" + C.HTTP_PORT;
		final HierarchyMode hierarchyMode = args.isPreserveHierarchy() ? HierarchyMode.PRESERVE : HierarchyMode.FLATTERN;
		LOG.info("hierarchyMode: {}", hierarchyMode);
		final MediaIndex index = new MediaIndex(contentTree, externalHttpContext, hierarchyMode);

		final Thread watcherThread = new Thread(new RunWatcher(args.getDirs(), index));
		watcherThread.setName("watcher");
		watcherThread.setDaemon(true);
		watcherThread.start();

		upnpService.getControlPoint().search(); // In case this helps announce our presence.  Untested.
		server.join(); // Keep app alive.
	}

	private static UpnpService makeUpnpServer () throws IOException {
		final Map<String, Resource<?>> pathToRes = new HashMap<>();

		final Icon icon = MediaServer.createDeviceIcon();
		final IconResource iconResource = new IconResource(icon.getUri(), icon);
		pathToRes.put("/icon.png", iconResource);

		return new UpnpServiceImpl() {
			@Override
			protected Registry createRegistry (final ProtocolFactory protocolFactory) {
				return new RegistryImplWithOverrides(this, pathToRes);
			}
		};
	}

	private static Server startContentServer (final ContentTree contentTree) throws Exception {
		int port = C.HTTP_PORT;
		while (true) {
			final HandlerList handler = makeContentHandler(contentTree);

			final Server server = new Server();
			server.setHandler(handler);
			server.addConnector(createHttpConnector(port));
			try {
				server.start();
				return server;
			}
			catch (final BindException e) {
				if ("Address already in use".equals(e.getMessage())) {
					port += 1;
				}
				else {
					throw e;
				}
			}
		}
	}

	private static HandlerList makeContentHandler (final ContentTree contentTree) {
		final ServletContextHandler servletHandler = new ServletContextHandler();
		servletHandler.setContextPath("/");
		servletHandler.addServlet(new ServletHolder(new ContentServlet(contentTree)), "/");
		servletHandler.addServlet(new ServletHolder(new IndexServlet(contentTree)), "/index/*");

		final HandlerList handler = new HandlerList();
		handler.setHandlers(new Handler[] { servletHandler });
		return handler;
	}

	private static SelectChannelConnector createHttpConnector (final int port) {
		final SelectChannelConnector connector = new SelectChannelConnector();
		connector.setStatsOn(false);
		connector.setPort(port);
		return connector;
	}

	private static void help (final CmdLineParser parser, final PrintStream ps) {
		ps.print("Usage: ");
		ps.print(C.APPNAME);
		parser.printSingleLineUsage(ps);
		ps.println();
		parser.printUsage(ps);
		ps.println();
	}

	private static class RunWatcher implements Runnable {

		private final List<File> roots;
		private final MediaIndex index;

		public RunWatcher (final List<File> roots, final MediaIndex index) {
			this.roots = roots;
			this.index = index;
		}

		@Override
		public void run () {
			try {
				new Watcher(this.roots, MediaFormat.MediaFileFilter.INSTANCE, this.index).run();
				LOG.error("Watcher thread exited.");
			}
			catch (final Exception e) { // NOSONAR
				LOG.error("Watcher thread died.", e);
			}
		}

	}

}
