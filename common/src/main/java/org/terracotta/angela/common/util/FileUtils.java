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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;

public class FileUtils {
  public static void setCorrectPermissions(Path dest) {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return;
    }

    try (Stream<Path> walk = Files.walk(dest)) {
      walk.filter(Files::isRegularFile)
          .filter(path -> {
            String name = path.getFileName().toString();
            return name.endsWith(".sh") || name.endsWith("tms.jar");
          })
          .forEach(path -> {
            try {
              Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(path));
              perms.addAll(EnumSet.of(OWNER_EXECUTE, GROUP_EXECUTE, OTHERS_EXECUTE));
              Files.setPosixFilePermissions(path, perms);
            } catch (IOException ioe) {
              throw new UncheckedIOException(ioe);
            }
          });
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  public static void createAndValidateDir(Path dirToCreate) {
    try {
      if (!Files.exists(dirToCreate)) {
        Files.createDirectories(dirToCreate);
      } else if (!Files.isDirectory(dirToCreate)) {
        throw new RuntimeException(dirToCreate.getFileName() + " is not a directory");
      }

      if (!Files.isWritable(dirToCreate)) {
        throw new RuntimeException(dirToCreate.getFileName() + " directory is not writable");
      }

      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(dirToCreate));
        perms.addAll(EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
        Files.setPosixFilePermissions(dirToCreate, perms);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static boolean deleteQuietly(Path path) {
    try {
      deleteTree(path);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static void copy(Path src, Path dest, CopyOption... copyOptions) {
    try {
      org.terracotta.utilities.io.Files.copy(src, dest, copyOptions);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void deleteTree(Path file) {
    try {
      org.terracotta.utilities.io.Files.deleteTree(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
