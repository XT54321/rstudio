/*
 * LearningState.hpp
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

#ifndef SESSION_LEARNING_STATE_HPP
#define SESSION_LEARNING_STATE_HPP

#include <core/json/Json.hpp>

namespace core {
   class Error;
   class FilePath;
}
 
namespace session {
namespace modules { 
namespace learning {
namespace state {


void init(const core::FilePath& directory);
void setSlideIndex(int index);


bool isActive();
core::FilePath directory();




void clear();


core::json::Value asJson();



core::Error initialize();

} // namespace state
} // namespace learning
} // namespace modules
} // namesapce session

#endif // SESSION_LEARNING_STATE_HPP
