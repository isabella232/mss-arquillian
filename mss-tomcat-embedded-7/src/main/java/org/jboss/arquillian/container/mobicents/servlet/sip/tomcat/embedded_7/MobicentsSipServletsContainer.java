/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.mobicents.servlet.sip.tomcat.embedded_7;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.logging.Logger;

import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Manager;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.deploy.ApplicationParameter;
import org.apache.catalina.startup.ExpandWar;
import org.apache.commons.io.FileUtils;
import org.jboss.arquillian.container.mobicents.api.MSSContainer;
import org.jboss.arquillian.container.mobicents.api.SipServletsEmbeddedContainer;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.jboss.shrinkwrap.mobicents.servlet.sip.api.ShrinkWrapSipStandardContext;
import org.mobicents.servlet.sip.SipConnector;
import org.mobicents.servlet.sip.annotation.ConcurrencyControlMode;
import org.mobicents.servlet.sip.catalina.SipStandardService;
import org.mobicents.servlet.sip.core.SipApplicationDispatcherImpl;
import org.mobicents.servlet.sip.core.session.SipStandardManager;
import org.mobicents.servlet.sip.startup.SipStandardContext;

/**
 * <p>Arquillian {@link DeployableContainer} implementation for an
 * Embedded Tomcat server; responsible for both lifecycle and deployment
 * operations.</p>
 *
 * <p>Please note that the context path set for the webapp must begin with
 * a forward slash. Otherwise, certain path operations within Tomcat
 * will behave inconsistently. Though it goes without saying, the host
 * name (bindAddress) cannot have a trailing slash for the same
 * reason.</p>
 *
 * @author <a href="mailto:jean.deruelle@gmail.com">Jean Deruelle</a>
 * @author Dan Allen
 * @version $Revision: $
 */
public class MobicentsSipServletsContainer implements DeployableContainer<MobicentsSipServletsConfiguration>, MSSContainer
{
    private static final Logger log = Logger.getLogger(MobicentsSipServletsContainer.class.getName());

    private static final String ENV_VAR = "${env.";

    private static final String TMPDIR_SYS_PROP = "java.io.tmpdir";

    private static final String SIP_PROTOCOL = "sip";

    protected List<SipConnector> sipConnectors;

    /**
     * Tomcat embedded
     */
    private SipServletsEmbeddedContainer embedded;

    /**
     * StandardContext
     */
    private SipStandardContext context;

    /**
     * Engine contained within Tomcat embedded
     */
    private Engine engine;

    /**
     * Host contained in the tomcat engine
     */
    private Host standardHost;

    private File appBase;

    /**
     * Tomcat container configuration
     */
    private MobicentsSipServletsConfiguration configuration;

    private SipStandardService sipStandardService;

    private String serverName;

    private String bindAddress;

    private int bindPort;

    private boolean wasStarted;

    @Inject @DeploymentScoped
    private InstanceProducer<SipStandardContext> sipStandardContextProducer;

    private Archive<?> archive;

    private Manager sipStandardManager;

    @Override
    public Class<MobicentsSipServletsConfiguration> getConfigurationClass()
    {
        return MobicentsSipServletsConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol()
    {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override
    public void setup(MobicentsSipServletsConfiguration configuration)
    {
        this.configuration = (MobicentsSipServletsConfiguration) configuration;
        bindAddress = this.configuration.getBindAddress();
        bindPort = this.configuration.getBindHttpPort();
        sipConnectors = getSipConnectors(this.configuration.getSipConnectors());
        serverName = this.configuration.getServerName();
    }

    @Override
    public List<SipConnector> getSipConnectors(String sipConnectorString) {
        List<SipConnector> connectors = new ArrayList<>();

        //SipConnector definition
        // bind_address:port/transport-lb_address:sip_udp_port:rmi_port
        // :5070/-127.0.0.1::2000 -> SipConnector: 127.0.0.1:5070/UDP LB: 127.0.0.1:5060 RMI 2000

        StringTokenizer tokenizer = new StringTokenizer(sipConnectorString, ",");
        while (tokenizer.hasMoreTokens()) {
            String connectorString = tokenizer.nextToken();
            String bindSipAddress;
            int bindSipPort = 0;
            String bindSipTransport = null;

            int indexOfColumn = connectorString.indexOf(":");
            int indexOfSlash = connectorString.indexOf("/");
            int indexOfDash = connectorString.indexOf("-");

            if(indexOfColumn == -1) {
                throw new IllegalArgumentException("sipConnectors configuration should be a comma separated list of <ip_address>:<port>/<transport>");
            }
            if(indexOfColumn == 0) {
                bindSipAddress = this.bindAddress;
                if (bindSipAddress == null) {
                    bindSipAddress = "127.0.0.1";
                }
            } else {
                bindSipAddress = connectorString.substring(0,indexOfColumn);
            }
            if(indexOfSlash != -1) {
                bindSipPort = Integer.parseInt(connectorString.substring(indexOfColumn + 1, indexOfSlash));
                if (indexOfDash == -1) {
                    bindSipTransport = connectorString.substring(indexOfSlash + 1);
                } else {
                    bindSipTransport = connectorString.substring(indexOfSlash + 1, indexOfDash);
                }
            } else if (indexOfSlash == -1) {
                bindSipTransport = "UDP";
                if (indexOfDash == -1) {
                    bindSipPort = Integer.parseInt(connectorString.substring(indexOfColumn + 1));
                } else {
                    bindSipPort = Integer.parseInt(connectorString.substring(indexOfColumn+1, indexOfDash));
                }
            }

            boolean useLoadBalancer = false;
            String loadBalancerAdress = null;
            int lbRmiPort = -1;
            int lbSipPort = -1;
            String loadBalancerStr;

            if (indexOfDash != -1) {
                useLoadBalancer = true;
                loadBalancerStr = connectorString.substring(indexOfDash+1);
                String[] lbTokens = loadBalancerStr.split(":");
                loadBalancerAdress = lbTokens[0];

                if (lbTokens[1] != null && !lbTokens[1].isEmpty()) {
                    lbSipPort = Integer.parseInt(lbTokens[1]);
                } else {
                    lbSipPort = 5060;
                }

                if (lbTokens[2] != null && !lbTokens[2].isEmpty()) {
                    lbRmiPort = Integer.parseInt(lbTokens[2]);
                } else {
                    lbRmiPort = 2000;
                }
            }

            SipConnector sipConnector = createSipConnector(bindSipAddress, bindSipPort, bindSipTransport);
            if (useLoadBalancer) {
                sipConnector.setUseLoadBalancer(useLoadBalancer);
                sipConnector.setLoadBalancerAddress(loadBalancerAdress);
                sipConnector.setLoadBalancerSipPort(lbSipPort);
                sipConnector.setLoadBalancerRmiPort(lbRmiPort);
            }

            connectors.add(sipConnector);
        }

        return connectors;
    }


    @Override
    public void startTomcatEmbedded() throws UnknownHostException,org.apache.catalina.LifecycleException, LifecycleException
    {
        startTomcatEmbedded(null);
    }

    @Override
    public void startTomcatEmbedded(Properties sipStackProperties) throws UnknownHostException, org.apache.catalina.LifecycleException, LifecycleException
    {

        System.setProperty("javax.servlet.sip.ar.spi.SipApplicationRouterProvider", configuration.getSipApplicationRouterProviderClassName());
        if (bindAddress != null){
            System.setProperty("org.mobicents.testsuite.testhostaddr",bindAddress);
        } else {
            System.setProperty("org.mobicents.testsuite.testhostaddr","127.0.0.1");
        }
        //Required in order to read the dar conf of each file
        //The Router in the arquillian.xml should be <property name="sipApplicationRouterProviderClassName">org.mobicents.servlet.sip.router.DefaultApplicationRouterProvider</property>
        String darConfiguration = Thread.currentThread().getContextClassLoader().getResource("test-dar.properties").toString();
        if (darConfiguration != null) {
            System.setProperty("javax.servlet.sip.dar", Thread.currentThread().getContextClassLoader().getResource("test-dar.properties").toString());
        } else {
            System.setProperty("javax.servlet.sip.dar", Thread.currentThread().getContextClassLoader().getResource("empty-dar.properties").toString());
        }

        // creating the tomcat embedded == service tag in server.xml
        MobicentsSipServletsEmbeddedImpl embedded= new MobicentsSipServletsEmbeddedImpl();
        this.embedded = embedded;
        sipStandardService = (SipStandardService)embedded.getService();
        sipStandardService.setSipApplicationDispatcherClassName(SipApplicationDispatcherImpl.class.getCanonicalName());
        sipStandardService.setCongestionControlCheckingInterval(-1);
        sipStandardService.setAdditionalParameterableHeaders("additionalParameterableHeader");
        sipStandardService.setUsePrettyEncoding(true);
        sipStandardService.setName(serverName);
        if (sipStackProperties != null)
            sipStandardService.setSipStackProperties(sipStackProperties);
        // TODO this needs to be a lot more robust
        String tomcatHome = configuration.getTomcatHome();
        File tomcatHomeFile = null;
        if (tomcatHome != null)
        {
            if (tomcatHome.startsWith(ENV_VAR))
            {
                String sysVar = tomcatHome.substring(ENV_VAR.length(), tomcatHome.length() - 1);
                tomcatHome = System.getProperty(sysVar);
                if (tomcatHome != null && tomcatHome.length() > 0 && new File(tomcatHome).isAbsolute())
                {
                    tomcatHomeFile = new File(tomcatHome);
                    log.info("Using tomcat home from environment variable: " + tomcatHome);
                }
            }
            else
            {
                tomcatHomeFile = new File(tomcatHome);
            }
        }

        if (tomcatHomeFile == null)
        {
            tomcatHomeFile = new File(System.getProperty(TMPDIR_SYS_PROP), "mss-tomcat-embedded-7");
        }


        tomcatHomeFile.mkdirs();
        embedded.setCatalinaBase(tomcatHomeFile.getAbsolutePath());
        embedded.setCatalinaHome(tomcatHomeFile.getAbsolutePath());

        // creates the engine, i.e., <engine> element in server.xml
        Engine engine = embedded.createEngine();
        this.engine = engine;
        engine.setName(serverName);
        engine.setDefaultHost(bindAddress);
        engine.setService(sipStandardService);
        sipStandardService.setContainer(engine);
        embedded.addEngine(engine);

        // creates the host, i.e., <host> element in server.xml
        appBase = new File(tomcatHomeFile, configuration.getAppBase());
        appBase.mkdirs();

        //Host configuration
        StandardHost host = (StandardHost) embedded.createHost(bindAddress, appBase.getAbsolutePath());
        this.standardHost = host;

        if (configuration.getTomcatWorkDir() != null)
        {
            host.setWorkDir(configuration.getTomcatWorkDir());
        }
        host.setUnpackWARs(configuration.isUnpackArchive());

        host.setDeployOnStartup(false);
        host.setAutoDeploy(false);

        embedded.getEngine().addChild(host);

        //Copy the license file
        try{

            System.setProperty("telscale.license.dir", tomcatHomeFile.getPath());

            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = Class.class.getClassLoader();
            }

            File license = FileUtils.toFile(classLoader.getResource("telestax-license.xml").toURI().toURL());
            if (license != null){
                FileUtils.copyFileToDirectory(license, tomcatHomeFile, false);
            } else {
                try {
                    InputStream is = classLoader.getResourceAsStream("telestax-license.xml");

                    OutputStream os = new FileOutputStream(tomcatHomeFile+File.separator+"telestax-license.xml");

                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    //read from is to buffer
                    while((bytesRead = is.read(buffer)) !=-1){
                        os.write(buffer, 0, bytesRead);
                    }
                    is.close();
                    //flush OutputStream to write any buffered data to file
                    os.flush();
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }


            }

        }catch(Exception e){
            e.printStackTrace();
        }

        //	       creates an http connector, i.e., <connector> element in server.xml
        Connector connector = embedded.createConnector(InetAddress.getByName(bindAddress), bindPort, false);
        embedded.setPort(bindPort);
        embedded.setHostname(bindAddress);
        embedded.addConnector(connector);
        embedded.setConnector(connector);

        // Enable JNDI - it is disabled by default.
        embedded.enableNaming();
        //
        // starts embedded Mobicents Sip Serlvets
        embedded.start();
        embedded.getService().start();

        // creates an sip connector, i.e., <connector> element in server.xml
        for (SipConnector sipConnector : sipConnectors) {
            addSipConnector(sipConnector);
        }

        wasStarted = true;
    }

    @Override
    public void stopTomcatEmbedded() throws org.jboss.arquillian.container.spi.client.container.LifecycleException, org.apache.catalina.LifecycleException
    {
        embedded.stop();
        wasStarted = false;
    }

    /**
     * Make sure an the unpacked WAR is not left behind
     * you would think Tomcat would cleanup an unpacked WAR, but it doesn't
     */
    @Override
    public void deleteUnpackedWAR(StandardContext standardContext)
    {
        File unpackDir = new File(standardHost.getAppBase(), standardContext.getPath().substring(1));
        if (unpackDir.exists())
        {
            ExpandWar.deleteDir(unpackDir);
        }
    }

    @Override
    public ProtocolMetaData deploy(final Archive<?> archive) throws org.jboss.arquillian.container.spi.client.container.DeploymentException
    {

        this.archive = archive;
        try
        {
            SipStandardContext sipStandardContext = archive.as(ShrinkWrapSipStandardContext.class);
            sipStandardContext.setXmlNamespaceAware(true);
            setSipStandardManager(new SipStandardManager());
            sipStandardContext.setManager(getSipStandardManager());
            sipStandardContext.addLifecycleListener(new EmbeddedContextConfig());
            sipStandardContext.setUnpackWAR(configuration.isUnpackArchive());
            sipStandardContext.setJ2EEServer("MSS-Arquillian-" + UUID.randomUUID().toString());

            //Set Context parameters
            if (configuration.getContextParam() != null){
                log.info("Setting contextParameters from configuration");
                String paramSeparator = configuration.getParamSeparator();
                String valueSeparator = configuration.getValueSeparator();
                String contextParams = configuration.getContextParam();
                String[] params = contextParams.split(paramSeparator);

                for (String param : params) {
                    String name = param.split(valueSeparator)[0];
                    String value = param.split(valueSeparator)[1];
                    ApplicationParameter applicationParameter = new ApplicationParameter();
                    applicationParameter.setName(name);
                    applicationParameter.setValue(value);
                    sipStandardContext.addApplicationParameter(applicationParameter);
                }
            }

            //Set ConcurrencyControlMode
            if (configuration.getConcurrencyControl() != null){
                sipStandardContext.setConcurrencyControlMode(ConcurrencyControlMode.valueOf(configuration.getConcurrencyControl()));
            }

            // Need to tell TomCat to use TCCL as parent, else the WebContextClassloader will be looking in AppCL
            sipStandardContext.setParentClassLoader(Thread.currentThread().getContextClassLoader());

            if (sipStandardContext.getUnpackWAR())
            {
                deleteUnpackedWAR(sipStandardContext);
            }

            //			// Override the default Tomcat WebappClassLoader, it delegates to System first. Half our testable app is on System classpath.
            //			WebappLoader webappLoader = new WebappLoader(sipStandardContext.getParentClassLoader());
            //			webappLoader.setDelegate(sipStandardContext.getDelegate());
            //			webappLoader.setLoaderClass(EmbeddedWebappClassLoader.class.getName());
            //			sipStandardContext.setLoader(webappLoader);

            standardHost.addChild(sipStandardContext);

            context = sipStandardContext;
            sipStandardContextProducer.set(sipStandardContext);

            String contextPath = sipStandardContext.getPath();
            SipContext sipContext = new SipContext(bindAddress, bindPort);

            for(String mapping : sipStandardContext.findServletMappings())
            {
                sipContext.add(new Servlet(
                        sipStandardContext.findServletMapping(mapping), contextPath));
            }

            return new ProtocolMetaData()
            .addContext(sipContext);
        }
        catch (Exception e)
        {
            throw new org.jboss.arquillian.container.spi.client.container.DeploymentException("Failed to deploy " + archive.getName(), e);
        }
    }

    @Override
    public void start() throws LifecycleException {
        try
        {
            startTomcatEmbedded();
        }
        catch (Exception e)
        {
            throw new LifecycleException("Bad shit happened", e);
        }
    }

    @Override
    public void stop() throws LifecycleException {
        try
        {
            //			removeFailedUnDeployments();
        }
        catch (Exception e)
        {
            throw new LifecycleException("Could not clean up", e);
        }
        if (wasStarted)
        {
            try
            {
                stopTomcatEmbedded();
            }
            catch (org.apache.catalina.LifecycleException e)
            {
                throw new LifecycleException("An unexpected error occurred", e);
            }
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        SipStandardContext sipStandardContext = sipStandardContextProducer.get();
        if (sipStandardContext != null)
        {
            standardHost.removeChild(sipStandardContext);
            if (sipStandardContext.getUnpackWAR())
            {
                deleteUnpackedWAR(sipStandardContext);
            }
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public List<SipConnector> getSipConnectors() {
        return sipConnectors;
    }

    @Override
    public boolean isStarted() {
        return wasStarted;
    }

    @Override
    public void addSipConnector(SipConnector sipConnector) throws LifecycleException {
        try {
            (sipStandardService).addSipConnector(sipConnector);
        } catch (Exception e) {
            throw new LifecycleException("Couldn't create the sip connector " + sipConnector, e);
        }
    }

    @Override
    public SipConnector createSipConnector(String ipAddress, int port, String transport)
    {
        SipConnector sipConnector = new SipConnector();
        sipConnector.setIpAddress(ipAddress);
        sipConnector.setPort(port);
        sipConnector.setTransport(transport);

        return sipConnector;
    }

    @Override
    public void removeSipConnector(String ipAddress, int port, String transport) throws LifecycleException {
        try {
            (sipStandardService).removeSipConnector(ipAddress, port, transport);
        } catch (Exception e) {
            throw new LifecycleException("Couldn't remove the sip connector " + ipAddress+":"+port+"/"+transport, e);
        }
    }

    @Override
    public Archive<?> getArchive()
    {
        return archive;
    }

    @Override
    public SipStandardService getSipStandardService() {
        return sipStandardService;
    }

    @Override
    public Manager getSipStandardManager() {
        return sipStandardManager;
    }

    @Override
    public void setSipStandardManager(Manager sipStandardManager) {
        this.sipStandardManager = sipStandardManager;
    }
}
