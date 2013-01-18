/*
 * LearningServerOperations.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.learning.model;

import org.rstudio.studio.client.server.*;
import org.rstudio.studio.client.server.Void;

public interface LearningServerOperations
{
   void setLearningSlideIndex(int index, 
                              ServerRequestCallback<Void> requestCallaback);
   
   void closeLearningPane(ServerRequestCallback<Void> requestCallaback);
   
   String getApplicationURL(String url);
}
