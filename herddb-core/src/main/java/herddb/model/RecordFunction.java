/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */

package herddb.model;

import herddb.utils.ObjectSizeUtils;

/**
 * Generic representation of a value computed on a Record
 *
 * @author enrico.olivelli
 */
public abstract class RecordFunction {

    public abstract byte[] computeNewValue(Record previous, StatementEvaluationContext context, TableContext tableContext) throws StatementExecutionException;

    /**
     * Estimate Object size for the PlanCache.
     * see {@link ObjectSizeUtils} for the limitations of this computation.
     */
    public int estimateObjectSizeForCache() {
        return ObjectSizeUtils.DEFAULT_OBJECT_SIZE_OVERHEAD;
    }

}
