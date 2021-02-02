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

package org.terracotta.angela.common.tcconfig;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;

/**
 * Created by esebasti on 7/21/17.
 */
public class License {

  private final String licenseContent;
  private final String filename;

  public License(URL licensePath) {
    this.filename = new File(licensePath.getFile()).getName();
    try (InputStream is = licensePath.openStream()) {
      if (is == null) {
        throw new IllegalArgumentException("License file is not present");
      }
      licenseContent = IOUtils.toString(is);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  public File writeToFile(File dest) {
    File licenseFile = new File(dest, this.filename);
    // tests are concurrently using a kit so do not re-write a license file
    if(!licenseFile.exists()) {
      try {
        Files.write(licenseFile.toPath(), licenseContent.getBytes());
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
    return licenseFile;
  }

  public String getFilename() {
    return filename;
  }
}
