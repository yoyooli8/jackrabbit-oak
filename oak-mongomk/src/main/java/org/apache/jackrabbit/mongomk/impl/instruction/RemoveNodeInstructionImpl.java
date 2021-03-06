/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.mongomk.impl.instruction;

import org.apache.jackrabbit.mongomk.api.instruction.InstructionVisitor;
import org.apache.jackrabbit.mongomk.api.instruction.Instruction.RemoveNodeInstruction;
import org.apache.jackrabbit.oak.commons.PathUtils;

/**
 * Implementation for the remove node operation => "-" STRING
 */
public class RemoveNodeInstructionImpl extends BaseInstruction implements RemoveNodeInstruction {

    /**
     * Constructs a new {@code RemoveNodeInstruction}.
     *
     * @param parentPath The parent path.
     * @param name The name
     */
    public RemoveNodeInstructionImpl(String parentPath, String name) {
        super(PathUtils.concat(parentPath, name));
    }

    @Override
    public void accept(InstructionVisitor visitor) {
        visitor.visit(this);
    }
}