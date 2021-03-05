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
package org.terracotta.angela.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.net.PortAllocator;
import java.util.Collections;

/**
 * @author Aurelien Broszniowski
 */
public class LocalAgentController extends AgentController {
  private final static Logger logger = LoggerFactory.getLogger(LocalAgentController.class);

  LocalAgentController(PortAllocator portAllocator) {
    super(null, Collections.emptyList(), 0, portAllocator);
  }

}
