/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.database.exceptions

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.query.metadata.Index

class UniqueIndexViolationException(
        entity: TransientEntity,
        index: Index
) : SimplePropertyValidationException(
        buildMessage(entity, index),
        "Value should be unique",
        entity,
        index.fields.first().name
)

private fun buildMessage(entity: TransientEntity, index: Index) = buildString {
    append("Index [$index] must be unique. Conflicting value: ")
    if (index.fields.isNotEmpty()) {
        index.fields.joinTo(this, ", ", prefix = "[", postfix = "]") { field ->
            if (field.isProperty) {
                entity.getProperty(field.name).toString()
            } else {
                entity.getLink(field.name).toString()
            }
        }
    } else {
        append("No accessible value")
    }
}