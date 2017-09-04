/*
 *  Copyright 2015 the original author or authors.
 *  @https://github.com/scouter-project/scouter
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package scouterx.webapp.main;

import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scouter.util.SysJMX;
import scouter.util.ThreadUtil;
import scouter.util.logo.Logo;
import scouterx.client.model.AgentModelThread;
import scouterx.client.net.LoginMgr;
import scouterx.client.net.LoginRequest;
import scouterx.client.server.ServerManager;
import scouterx.client.thread.ServerSessionObserver;
import scouterx.webapp.configure.ConfigureAdaptor;
import scouterx.webapp.configure.ConfigureManager;
import scouterx.webapp.configure.ServerConfig;

import java.io.File;
import java.util.List;
import java.util.TimeZone;

/**
 * Created by gunlee on 2017. 8. 25.
 */
public class WebAppMain {
	public static void main(String[] args) {
		Logo.print(true);
        initializeLogDir();

        ConfigureAdaptor conf = ConfigureManager.getConfigure();
        connectScouterCollector();

        org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(conf.getNetHttpPort());
		setWebAppContext(server, false);

		try {
			server.start();
            waitOnExit(server);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

    private static void connectScouterCollector() {
        Logger log = LoggerFactory.getLogger(WebAppMain.class);

        List<ServerConfig> serverConfigList = ConfigureManager.getConfigure().getServerConfigs();
        ServerManager srvMgr = ServerManager.getInstance();
        for (ServerConfig serverConfig : serverConfigList) {
            scouterx.client.server.Server server = new scouterx.client.server.Server(serverConfig.getIp(), serverConfig.getPort());
            if (srvMgr.getServer(server.getId()) == null) {
                srvMgr.addServer(server);
            } else {
                server = srvMgr.getServer(server.getId());
            }

            LoginRequest result = LoginMgr.login(server.getId(), serverConfig.getId(), serverConfig.getPassword(), true);
            if (result.success) {
                log.info("Successfully log in to {}:{}", server.getIp(), server.getPort());
                AgentModelThread.getInstance(); //preloading
            } else {
                server.setUserId(serverConfig.getId());
                server.setPassword(serverConfig.getPassword());
                log.error("Fail to log in to {}:{}", server.getIp(), server.getPort());
            }
        }
        ServerSessionObserver.load();
    }

    private static void waitOnExit(Server server) throws Exception {
        File exit = new File(SysJMX.getProcessPID() + ".scouter");
        try {
            exit.createNewFile();
        } catch (Exception e) {
            String tmp = System.getProperty("user.home", "/tmp");
            exit = new File(tmp, SysJMX.getProcessPID() + ".scouter.run");
            try {
                exit.createNewFile();
            } catch (Exception k) {
                System.exit(1);
            }
        }
        exit.deleteOnExit();
        System.out.println("System JRE version : " + System.getProperty("java.version"));
        while (true) {
            if (exit.exists() == false) {
                server.setStopTimeout(3000);
                server.stop();
                System.exit(0);
            }
            ThreadUtil.sleep(1000);
        }
    }

    private static void initializeLogDir() {
        ConfigureAdaptor conf = ConfigureManager.getConfigure();

        File logDir = new File(conf.getLogDir());
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        System.setProperty("scouter_webapp_log_dir", conf.getLogDir());
        Logger firstLogger = LoggerFactory.getLogger(WebAppMain.class);
        firstLogger.info("scouter webapp starting!");
    }

    /**
     * This method id referenced from scouter server when the option net_http_api_enabled is true
     */
    public static void setWebAppContext(Server server) {
        setWebAppContext(server, true);
    }

    private static void setWebAppContext(Server server, boolean isStandAlone) {
        ConfigureAdaptor conf = ConfigureManager.getConfigure();

        if (isStandAlone) {
            initializeLogDir();
        }

		HandlerCollection handlers = new HandlerCollection();
		NCSARequestLog requestLog = new NCSARequestLog();
		requestLog.setFilename("./logs/http-request-yyyy_mm_dd.log");
		requestLog.setFilenameDateFormat("yyyy_MM_dd");
		requestLog.setRetainDays(conf.getLogKeepDays());
		requestLog.setAppend(true);
		requestLog.setExtended(true);
		requestLog.setLogCookies(false);
		requestLog.setLogTimeZone(TimeZone.getDefault().getID());
		RequestLogHandler requestLogHandler = new RequestLogHandler();
		requestLogHandler.setRequestLog(requestLog);

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.getSessionHandler().setMaxInactiveInterval(conf.getNetHttpApiSessionTimeout());

        context.setBaseResource(Resource.newClassPathResource("/webroot"));

		context.setContextPath("/");
		handlers.addHandler(requestLogHandler);
		handlers.addHandler(context);

		server.setHandler(handlers);

		ServletHolder jerseyHolder = new ServletHolder(ServletContainer.class);
		jerseyHolder.setInitParameter("jersey.config.server.provider.packages", "scouterx.webapp");
		context.addServlet(jerseyHolder, "/scouter/*");

		ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
		defaultHolder.setInitParameter("dirAllowed","false");
		context.addServlet(defaultHolder,"/");
	}
}
