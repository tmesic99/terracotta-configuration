/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Configuration.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 *
 */

package org.terracotta.config;


import org.terracotta.config.util.DefaultSubstitutor;
import org.terracotta.config.util.ParameterSubstitutor;
import org.terracotta.entity.ServiceProviderConfiguration;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.List;
import org.terracotta.config.service.ServiceConfigParser;
import org.terracotta.config.service.ExtendedConfigParser;

public class TCConfigurationParser {

  private static final SchemaFactory XSD_SCHEMA_FACTORY = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
  public static final URL TERRACOTTA_XML_SCHEMA = TCConfigurationParser.class.getResource("/terracotta.xsd");
  private static final String WILDCARD_IP = "0.0.0.0";
  public static final int MIN_PORTNUMBER = 0x0FFF;
  public static final int MAX_PORTNUMBER = 0xFFFF;
  public static final String DEFAULT_LOGS = "logs";

  private static final Map<URI, ServiceConfigParser> serviceParsers = new HashMap<>();
  private static final Map<URI, ExtendedConfigParser> configParsers = new HashMap<>();

  private static TcConfiguration parseStream(InputStream in, ErrorHandler eh, String source, ClassLoader loader) throws IOException, SAXException {
    Collection<Source> schemaSources = new ArrayList<>();

    schemaSources.add(new StreamSource(TERRACOTTA_XML_SCHEMA.openStream()));

    for (ServiceConfigParser parser : loadServiceConfigurationParserClasses(loader)) {
      schemaSources.add(parser.getXmlSchema());
      serviceParsers.put(parser.getNamespace(), parser);
    }
    for (ExtendedConfigParser parser : loadConfigurationParserClasses(loader)) {
      schemaSources.add(parser.getXmlSchema());
      configParsers.put(parser.getNamespace(), parser);
    }

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setIgnoringComments(true);
    factory.setIgnoringElementContentWhitespace(true);
    factory.setSchema(XSD_SCHEMA_FACTORY.newSchema(schemaSources.toArray(new Source[schemaSources.size()])));

    final DocumentBuilder domBuilder;
    try {
      domBuilder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new AssertionError(e);
    }
    domBuilder.setErrorHandler(eh);
    final Element config = domBuilder.parse(in).getDocumentElement();

    try {
      JAXBContext jc = JAXBContext.newInstance("org.terracotta.config", TCConfigurationParser.class.getClassLoader());
      Unmarshaller u = jc.createUnmarshaller();

      TcConfig tcConfig = u.unmarshal(config, TcConfig.class).getValue();
      if(tcConfig.getServers() == null) {
        Servers servers = new Servers();
        tcConfig.setServers(servers);
      }

      if(tcConfig.getServers().getServer().isEmpty()) {
        tcConfig.getServers().getServer().add(new Server());
      }
      DefaultSubstitutor.applyDefaults(tcConfig);
      applyPlatformDefaults(tcConfig, source);

      List<ServiceProviderConfiguration> serviceConfigurations = new ArrayList<>();
      List<Object> configObjects = new ArrayList<>();
      if (tcConfig.getPlugins()!= null && tcConfig.getPlugins().getConfigOrService()!= null) {
        //now parse the service configuration.
        for (Object plugin : tcConfig.getPlugins().getConfigOrService()) {
          if(plugin instanceof Service) {
            Element element = ((Service) plugin).getServiceContent();
            URI namespace = URI.create(element.getNamespaceURI());
            ServiceConfigParser parser = serviceParsers.get(namespace);
            if (parser == null) {
              throw new TCConfigurationSetupException("Can't find parser for service " + namespace);
            }
            ServiceProviderConfiguration serviceProviderConfiguration = parser.parse(element, source);
            serviceConfigurations.add(serviceProviderConfiguration);
          } else if(plugin instanceof Config) {
            Element element = ((Config) plugin).configContent;
            URI namespace = URI.create(element.getNamespaceURI());
            ExtendedConfigParser parser = configParsers.get(namespace);
            if (parser == null) {
              throw new TCConfigurationSetupException("Can't find parser for config " + namespace);
            }
            Object co = parser.parse(element, source);
            configObjects.add(co);
          }
        }
      }

      return new TcConfiguration(tcConfig, source, configObjects, serviceConfigurations);
    } catch (JAXBException e) {
      throw new TCConfigurationSetupException(e);
    }
  }

  private static void applyPlatformDefaults(TcConfig tcConfig, String source) {
    for(Server server : tcConfig.getServers().getServer()) {
      TCConfigurationParser.setDefaultBind(server);
      TCConfigurationParser.initializeTsaPort(server);
      TCConfigurationParser.initializeManagementPort(server);
      TCConfigurationParser.initializeTsaGroupPort(server);
      TCConfigurationParser.initializeNameAndHost(server);
      TCConfigurationParser.initializeLogsDirectory(server, source);
    }
  }

  private static void initializeTsaPort(Server server) {
    if(server.getTsaPort() == null) {
      BindPort tsaPort = new BindPort();
      tsaPort.setValue(TCConfigDefaults.TSA_PORT);
      server.setTsaPort(tsaPort);
    }
    if (server.getTsaPort().getBind() == null) {
      server.getTsaPort().setBind(server.getBind());
    }
  }

  private static void initializeLogsDirectory(Server server, String source) {
    if(server.getLogs() == null) {
      server.setLogs(DEFAULT_LOGS);
    }
    server.setLogs(getAbsolutePath(ParameterSubstitutor.substitute(server.getLogs()), new File(source!= null ? source: ".")));
  }

  private static String getAbsolutePath(String substituted, File directoryLoadedFrom) {
    File out = new File(substituted);
    if (!out.isAbsolute()) {
      out = new File(directoryLoadedFrom, substituted);
    }

    return out.getAbsolutePath();
  }

  private static void initializeManagementPort(Server server) {
    if (server.getManagementPort() == null) {
      BindPort managementPort = new BindPort();
      server.setManagementPort(managementPort);
      int defaultManagementPort = computeManagementPortFromTSAPort(server.getTsaPort().getValue());

      managementPort.setValue(defaultManagementPort);
      managementPort.setBind(server.getBind());
    } else if (server.getManagementPort().getBind() == null) {
      server.getManagementPort().setBind(server.getBind());
    }
  }


  public static int computeManagementPortFromTSAPort(int tsaPort) {
    int tempPort = tsaPort + TCConfigDefaults.MANAGEMENTPORT_OFFSET_FROM_TSAPORT;
    return ((tempPort <= MAX_PORTNUMBER) ? tempPort : (tempPort % MAX_PORTNUMBER) + MIN_PORTNUMBER);
  }

  private static void initializeTsaGroupPort(Server server) {
    if (server.getTsaGroupPort() == null) {
      BindPort l2GrpPort = new BindPort();
      server.setTsaGroupPort(l2GrpPort);
      int tempGroupPort = server.getTsaPort().getValue() + TCConfigDefaults.GROUPPORT_OFFSET_FROM_TSAPORT;
      int defaultGroupPort = ((tempGroupPort <= MAX_PORTNUMBER) ? (tempGroupPort) : (tempGroupPort % MAX_PORTNUMBER) + MIN_PORTNUMBER);
      l2GrpPort.setValue(defaultGroupPort);
      l2GrpPort.setBind(server.getBind());
    } else if (server.getTsaGroupPort().getBind() == null) {
      server.getTsaGroupPort().setBind(server.getBind());
    }
  }

  private static void initializeNameAndHost(Server server) {
    if (server.getHost() == null || server.getHost().trim().length() == 0) {
      if (server.getName() == null) {
        server.setHost("%i");
      } else {
        server.setHost(server.getName());
      }
    }

    if (server.getName() == null || server.getName().trim().length() == 0) {
      int tsaPort = server.getTsaPort().getValue();
      server.setName(server.getHost() + (tsaPort > 0 ? ":" + tsaPort : ""));
    }

    // CDV-77: add parameter expansion to the <server> attributes ('host' and 'name')
    server.setHost(ParameterSubstitutor.substitute(server.getHost()));
    server.setName(ParameterSubstitutor.substitute(server.getName()));
  }
  private static void setDefaultBind(Server s) {
    if (s.getBind() == null || s.getBind().trim().length() == 0) {
      s.setBind(WILDCARD_IP);
    }
    s.setBind(ParameterSubstitutor.substitute(s.getBind()));
  }

  private static TcConfiguration convert(InputStream in, String path, ClassLoader loader) throws IOException, SAXException {
    byte[] data = new byte[in.available()];
    in.read(data);
    in.close();
    ByteArrayInputStream bais = new ByteArrayInputStream(data);

    return parseStream(bais, RethrowErrorHandler.INSTANCE, path, loader);
  }
  
  public static TcConfiguration parse(File f)  throws IOException, SAXException {
    return parse(f, Thread.currentThread().getContextClassLoader());
  }

  public static TcConfiguration parse(File file, ClassLoader loader) throws IOException, SAXException {
    try (FileInputStream in = new FileInputStream(file)) {
      return convert(in, file.getParent(), loader);
    }
  }
  
  public static TcConfiguration parse(String xmlText) throws IOException, SAXException {
    return parse(xmlText, Thread.currentThread().getContextClassLoader());
  }

  public static TcConfiguration parse(String xmlText, ClassLoader loader) throws IOException, SAXException {
    return convert(new ByteArrayInputStream(xmlText.getBytes()), null, loader);
  }
  
  public static TcConfiguration parse(InputStream stream) throws IOException, SAXException {
    return parse(stream, Thread.currentThread().getContextClassLoader());
  }
  
  public static TcConfiguration parse(InputStream stream, ClassLoader loader) throws IOException, SAXException {
    return convert(stream, null, loader);
  }
  
  public static TcConfiguration parse(URL stream) throws IOException, SAXException {
    return parse(stream, Thread.currentThread().getContextClassLoader());
  }
  
  public static TcConfiguration parse(URL url, ClassLoader loader) throws IOException, SAXException {
    return convert(url.openStream(), url.getPath(), loader);
  }
  
  public static TcConfiguration parse(InputStream in, Collection<SAXParseException> errors, String source) throws IOException, SAXException {
    return parse(in, errors, source, Thread.currentThread().getContextClassLoader());
  }
  
  public static TcConfiguration parse(InputStream in, Collection<SAXParseException> errors, String source, ClassLoader loader) throws IOException, SAXException {
    return parseStream(in, new CollectingErrorHandler(errors), source, loader);
  }

  private static class CollectingErrorHandler implements ErrorHandler {

    private final Collection<SAXParseException> errors;

    CollectingErrorHandler(Collection<SAXParseException> errors) {
      this.errors = errors;
    }

    @Override
    public void error(SAXParseException exception) throws SAXException {
      errors.add(exception);
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
      errors.add(exception);
    }

    @Override
    public void warning(SAXParseException exception) throws SAXException {
      errors.add(exception);
    }

  }

  private static class RethrowErrorHandler implements ErrorHandler {

    public static final ErrorHandler INSTANCE = new RethrowErrorHandler();

    @Override
    public void warning(SAXParseException exception) throws SAXException {
      throw exception;
    }

    @Override
    public void error(SAXParseException exception) throws SAXException {
      throw exception;
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
      throw exception;
    }
  }

  private static ServiceLoader<ServiceConfigParser> loadServiceConfigurationParserClasses(ClassLoader loader) {
    return ServiceLoader.load(ServiceConfigParser.class,loader);
  }
  

  private static ServiceLoader<ExtendedConfigParser> loadConfigurationParserClasses(ClassLoader loader) {
    return ServiceLoader.load(ExtendedConfigParser.class,loader);
  }  
}
