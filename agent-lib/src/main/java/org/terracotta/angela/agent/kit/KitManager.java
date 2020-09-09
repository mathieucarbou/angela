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

package org.terracotta.angela.agent.kit;

import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static org.terracotta.angela.agent.Agent.ROOT_DIR;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.PackageType.SAG_INSTALLER;

/**
 * @author Aurelien Broszniowski
 */
public abstract class KitManager {
  private static final Logger logger = LoggerFactory.getLogger(KitManager.class);
  private static final long STALE_SNAPSHOT_LIMIT_HOURS = TimeUnit.DAYS.toHours(1);

  protected final Distribution distribution;
  final Path rootInstallationPath;  // the work directory where installs are stored for caching
  Path kitInstallationPath; // the extracted install to be used as a cached install

  KitManager(Distribution distribution) {
    this.distribution = distribution;
    this.rootInstallationPath = distribution == null ? null : buildRootInstallationPath(distribution);
  }

  private Path buildRootInstallationPath(Distribution distribution) {
    PackageType packageType = distribution.getPackageType();
    if (packageType != KIT && packageType != SAG_INSTALLER) {
      // Can happen if someone adds a new packageType but doesn't provide a suitable handling here
      throw new RuntimeException("Can not resolve the local kit distribution package: " + packageType);
    }

    Path sagOrKitDir = ROOT_DIR.resolve(packageType == SAG_INSTALLER ? "sag" : "kits");
    FileUtils.createAndValidateDir(sagOrKitDir);
    return sagOrKitDir.resolve(distribution.getVersion().getVersion(true));
  }

  /**
   * resolve installer file location
   *
   * @param offline
   * @return location of the installer archive file
   */
  boolean isValidLocalInstallerFilePath(boolean offline, Path localInstallerFile) {
    if (!Files.isRegularFile(localInstallerFile)) {
      logger.info("Kit {} is not an existing file", localInstallerFile.toAbsolutePath());
      return false;
    }

    long timeSinceLastModified;
    try {
      // Use the archive to check the modified time, because the timestamp on the inflated directory corresponds to the creation of the archive, and not to its download
      timeSinceLastModified = System.currentTimeMillis() - Files.getLastModifiedTime(localInstallerFile).toMillis();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    if (!offline && distribution.getVersion().isSnapshot() && timeSinceLastModified > STALE_SNAPSHOT_LIMIT_HOURS * 60 * 60 * 1000) {
      logger.info("Mode is online, distribution is snapshot, and {} is older than {} hours", localInstallerFile.getFileName(), STALE_SNAPSHOT_LIMIT_HOURS);
      FileUtils.deleteQuietly(localInstallerFile.getParent());
      return false;
    }

    // snapshots and SAG installer have no MD5
    if (distribution.getVersion().isSnapshot() || distribution.getPackageType() != KIT) {
      return true;
    }

    String md5File = localInstallerFile.toAbsolutePath().toString() + ".md5";
    String md5FileHash;
    try {
      byte[] bytes = Files.readAllBytes(Paths.get(md5File));
      md5FileHash = new String(bytes, StandardCharsets.US_ASCII).trim();
    } catch (NoSuchFileException nsfe) {
      // no MD5 file? let's consider the archive corrupt
      logger.warn("{} does not have corresponding {} secure hash file on disk, considering it corrupt", localInstallerFile, md5File);
      FileUtils.deleteQuietly(localInstallerFile.getParent());
      return false;
    } catch (IOException ioe) {
      throw new RuntimeException("Error reading " + md5File, ioe);
    }

    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      try (InputStream fis = Files.newInputStream(localInstallerFile)) {
        byte[] buffer = new byte[8192];
        while (true) {
          int read = fis.read(buffer);
          if (read == -1) {
            break;
          } else {
            md.update(buffer, 0, read);
          }
        }
      }
      String localInstallerFileHash = DatatypeConverter.printHexBinary(md.digest());

      if (!localInstallerFileHash.equalsIgnoreCase(md5FileHash)) {
        // MD5 does not match? let's consider the archive corrupt
        logger.warn("{} secure hash does not match the contents of {} secure hash file on disk, considering it corrupt", localInstallerFile, md5File);
        FileUtils.deleteQuietly(localInstallerFile.getParent());
        return false;
      }
    } catch (NoSuchAlgorithmException nsae) {
      throw new RuntimeException("Missing MD5 secure hash implementation", nsae);
    } catch (IOException ioe) {
      throw new RuntimeException("Error reading " + localInstallerFile, ioe);
    }

    return true;
  }

  public Distribution getDistribution() {
    return distribution;
  }

  public Path getKitInstallationPath() {
    return kitInstallationPath;
  }
}
