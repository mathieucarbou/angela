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
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.common.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Find current java location
 *
 * @author Aurelien Broszniowski
 */

public class JavaLocationResolver {

  private final List<JDK> jdks;

  public JavaLocationResolver() {
    jdks = findJDKs();
  }

  public JavaLocationResolver(InputStream inputStream) {
    Objects.requireNonNull(inputStream);
    try {
      jdks = findJDKs(inputStream);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public List<JDK> resolveJavaLocations(String version, Set<String> vendors, boolean checkValidity) {
    List<JDK> list = jdks.stream()
        .filter(jdk -> !checkValidity || jdk.isValid())
        .filter(jdk -> version.isEmpty() || version.equals(jdk.getVersion()))
        .filter(jdk -> vendors.isEmpty() || vendors.stream().anyMatch(v -> v.equalsIgnoreCase(jdk.getVendor())))
        .collect(Collectors.toList());
    if (list.isEmpty()) {
      String message = "Missing JDK with version [" + version + "]";
      if (vendors != null) {
        message += " and one vendor in [" + vendors + "]";
      }
      message += " config in toolchains.xml. Available JDKs: " + jdks;
      throw new RuntimeException(message);
    }
    return list;
  }

  private static List<JDK> findJDKs() {
    try {
      List<JDK> jdks = findJDKs(new File(System.getProperty("user.home") + File.separator + ".m2" + File.separator + "toolchains.xml").toURI().toURL());
      return Collections.unmodifiableList(jdks);
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  private static List<JDK> findJDKs(URL toolchainsLocation) {
    try (InputStream is = toolchainsLocation.openStream()) {
      return findJDKs(is);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<JDK> findJDKs(InputStream is) throws ParserConfigurationException, SAXException, IOException {
    List<JDK> jdks = new ArrayList<>();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document doc = dBuilder.parse(is);
    doc.getDocumentElement().normalize();

    NodeList toolchainList = doc.getElementsByTagName("toolchain");
    for (int i = 0; i < toolchainList.getLength(); i++) {
      Element toolchainElement = (Element) toolchainList.item(i);

      Element providesElement = (Element) toolchainElement.getElementsByTagName("provides").item(0);
      Element configurationElement = (Element) toolchainElement.getElementsByTagName("configuration").item(0);

      String home = configurationElement.getElementsByTagName("jdkHome").item(0).getTextContent();
      boolean valid = new File(home).isDirectory();

      String version = textContentOf(providesElement, "version");
      String vendor = textContentOf(providesElement, "vendor");

      jdks.add(new JDK(home, version, vendor, valid));
    }

    return jdks;
  }

  private static String textContentOf(Element element, String subElementName) {
    NodeList nodeList = element.getElementsByTagName(subElementName);
    if (nodeList.getLength() > 0) {
      return nodeList.item(0).getTextContent();
    }
    return null;
  }

}
