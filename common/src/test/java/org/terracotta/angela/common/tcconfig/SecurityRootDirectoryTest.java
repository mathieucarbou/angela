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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThat;

/**
 * @author vmad
 */
public class SecurityRootDirectoryTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static void verifyDirectories(Path actualSecurityRootDirectory, Path deserializedSecurityRootDirectory) throws IOException {
    Path actualIdentityDir = actualSecurityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
    Path actualTrustedAuthorityDir = actualSecurityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
    Path actualAccessControlDir = actualSecurityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);

    if (Files.exists(actualIdentityDir)) {
      final Path deserializedIdentityDir = deserializedSecurityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
      assertThat(Files.exists(deserializedIdentityDir), is(true));
      verifyFiles(actualIdentityDir, deserializedIdentityDir);
    }
    if (Files.exists(actualTrustedAuthorityDir)) {
      final Path deserializedTrustedAuthorityDir = deserializedSecurityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
      assertThat(Files.exists(deserializedTrustedAuthorityDir), is(true));
      verifyFiles(actualTrustedAuthorityDir, deserializedTrustedAuthorityDir);
    }
    if (Files.exists(actualAccessControlDir)) {
      final Path deserializedAccessControlDir = deserializedSecurityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);
      assertThat(Files.exists(deserializedAccessControlDir), is(true));
      verifyFiles(actualAccessControlDir, deserializedAccessControlDir);
    }
  }

  private static void verifyFiles(Path actualDirectory, Path deserializedDirectory) throws IOException {
    try (Stream<Path> actual = Files.list(actualDirectory);
         Stream<Path> deserialized = Files.list(deserializedDirectory)) {
      assertThat(deserialized.count(), is(actual.count()));
    }

    try (Stream<Path> actual = Files.list(actualDirectory)) {
      actual.forEach(actualPath -> {
          Path deserializedPath = deserializedDirectory.resolve(actualPath.getFileName());
          assertThat(Files.exists(deserializedPath), is(true));
          try {
            byte[] expectedFileContents = Files.readAllBytes(deserializedPath);
            byte[] actualFileContents = Files.readAllBytes(actualPath);
            assertArrayEquals(expectedFileContents, actualFileContents);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      );
    }
  }

  @Test
  public void testSerialization() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path identityDir = securityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
    Files.createDirectory(identityDir);
    Files.write(identityDir.resolve("file1"), "identity-file1 contents".getBytes());
    Files.write(identityDir.resolve("file2"), "identity-file2 contents".getBytes());

    Path trustedAuthorityDir = securityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
    Files.createDirectory(trustedAuthorityDir);
    Files.write(trustedAuthorityDir.resolve("file1"), "trusted-authority-file1 contents".getBytes());
    Files.write(trustedAuthorityDir.resolve("file2"), "trusted-authority-file2 contents".getBytes());

    Path accessControlDir = securityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);
    Files.createDirectory(accessControlDir);
    Files.write(accessControlDir.resolve("file1"), "access-control-file1 contents".getBytes());
    Files.write(accessControlDir.resolve("file2"), "access-control-file2 contents".getBytes());

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithIdentityDirectoryOnly() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path identityDir = securityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
    Files.createDirectory(identityDir);
    Files.write(identityDir.resolve("file1"), "identity-file1 contents".getBytes());
    Files.write(identityDir.resolve("file2"), "identity-file2 contents".getBytes());

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithEmptyIdentityDirectory() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path identityDir = securityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
    Files.createDirectory(identityDir);

    Path trustedAuthorityDir = securityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
    Files.createDirectory(trustedAuthorityDir);
    Path accessControlDir = securityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);
    Files.createDirectory(accessControlDir);

    Files.write(trustedAuthorityDir.resolve("file1"), "trusted-authority-file1 contents".getBytes());
    Files.write(trustedAuthorityDir.resolve("file2"), "trusted-authority-file2 contents".getBytes());
    Files.write(accessControlDir.resolve("file1"), "access-control-file1 contents".getBytes());
    Files.write(accessControlDir.resolve("file2"), "access-control-file2 contents".getBytes());

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithTrustedAuthorityDirectoryOnly() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path trustedAuthorityDir = securityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
    Files.createDirectory(trustedAuthorityDir);
    Files.write(trustedAuthorityDir.resolve("file1"), "trusted-authority-file1 contents".getBytes());
    Files.write(trustedAuthorityDir.resolve("file2"), "trusted-authority-file2 contents".getBytes());

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithEmptyTrustedAuthorityDirectory() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path identityDir = securityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
    Files.createDirectory(identityDir);
    Path accessControlDir = securityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);
    Files.createDirectory(accessControlDir);

    Files.write(identityDir.resolve("file1"), "identity-file1 contents".getBytes());
    Files.write(identityDir.resolve("file2"), "identity-file2 contents".getBytes());
    Files.write(accessControlDir.resolve("file1"), "access-control-file1 contents".getBytes());
    Files.write(accessControlDir.resolve("file2"), "access-control-file2 contents".getBytes());

    Path trustedAuthorityDir = securityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
    Files.createDirectory(trustedAuthorityDir);

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithAccessControlDirectoryOnly() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path accessControlDir = securityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);
    Files.createDirectory(accessControlDir);
    Files.write(accessControlDir.resolve("file1"), "access-control-file1 contents".getBytes());
    Files.write(accessControlDir.resolve("file2"), "access-control-file2 contents".getBytes());

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithEmptyAccessControlDirectory() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();

    Path identityDir = securityRootDirectory.resolve(SecurityRootDirectory.IDENTITY_DIR_NAME);
    Files.createDirectory(identityDir);
    Path trustedAuthorityDir = securityRootDirectory.resolve(SecurityRootDirectory.TRUSTED_AUTHORITY_DIR_NAME);
    Files.createDirectory(trustedAuthorityDir);

    Files.write(identityDir.resolve("file1"), "identity-file1 contents".getBytes());
    Files.write(identityDir.resolve("file2"), "identity-file2 contents".getBytes());
    Files.write(trustedAuthorityDir.resolve("file1"), "trusted-authority-file1 contents".getBytes());
    Files.write(trustedAuthorityDir.resolve("file2"), "trusted-authority-file2 contents".getBytes());

    Path accessControlDir = securityRootDirectory.resolve(SecurityRootDirectory.ACCESS_CONTROL_DIR_NAME);
    Files.createDirectory(accessControlDir);

    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithKeyStoreFiles() throws Exception {
    Path securityRootDirectory = Paths.get(getClass().getResource("/security").toURI());
    serializeAndVerify(securityRootDirectory);
  }

  @Test
  public void testSerializationWithEmptyDirectory() throws Exception {
    Path securityRootDirectory = temporaryFolder.newFolder().toPath();
    serializeAndVerify(securityRootDirectory);
  }

  private void serializeAndVerify(Path actualSecurityRootDirectory) throws Exception {
    SecurityRootDirectory securityRootDirectory = SecurityRootDirectory.securityRootDirectory(actualSecurityRootDirectory.toUri().toURL());

    File serializedDataFile = temporaryFolder.newFile();
    FileOutputStream fileOutputStream = new FileOutputStream(serializedDataFile);
    ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
    objectOutputStream.writeObject(securityRootDirectory);

    FileInputStream fileInputStream = new FileInputStream(serializedDataFile);
    ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
    SecurityRootDirectory deserializedSecurityRootDirectory = (SecurityRootDirectory) objectInputStream.readObject();

    Path deserializedSecurityRootDirectoryPath = temporaryFolder.newFolder().toPath();
    deserializedSecurityRootDirectory.createSecurityRootDirectory(deserializedSecurityRootDirectoryPath);

    verifyDirectories(actualSecurityRootDirectory, deserializedSecurityRootDirectoryPath);
  }
}