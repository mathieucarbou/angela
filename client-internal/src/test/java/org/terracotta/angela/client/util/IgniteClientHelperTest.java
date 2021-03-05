/*
 * Copyright 2021 mscott2.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.angela.client.util;

import java.io.File;
import java.util.Collections;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.InstanceId;

/**
 *
 */
public class IgniteClientHelperTest {

  public IgniteClientHelperTest() {
  }

  @BeforeClass
  public static void setUpClass() {
  }

  @AfterClass
  public static void tearDownClass() {
  }

  @Before
  public void setUp() {
  }

  @After
  public void tearDown() {
  }

  @Test(expected=IllegalStateException.class)
  public void testExecuteRemotelyAsyncThrowsWhenLocal() {
    IgniteClientHelper.executeRemotelyAsync(null, "localhost", 0, ()->{});
  }

  @Test(expected=IllegalStateException.class)
  public void testUploadClientJarsWhenLocal() throws Exception {
    IgniteClientHelper.uploadClientJars(null, "localhost", 0, mock(InstanceId.class), Collections.emptyList());
  }

  @Test(expected=IllegalStateException.class)
  public void testUploadKitWhenLocal() throws Exception {
    IgniteClientHelper.uploadKit(null, "localhost", 0, mock(InstanceId.class), mock(Distribution.class), "kit", mock(File.class));
  }

  @Test
  public void testExecuteRemotelyRedirectsToLocal() throws Exception {
    String val = IgniteClientHelper.executeRemotely(null, "localhost", 0, ()->"test");
    Assert.assertEquals("test", val);
  }
}
