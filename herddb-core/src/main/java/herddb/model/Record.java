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

import herddb.utils.Bytes;

/**
 * A generic record
 *
 * @author enrico.olivelli
 */
public class Record {

    public final Bytes key;
    public final Bytes value;

    public Record(Bytes key, Bytes value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public String toString() {
        return "Record{" + "key=" + key + ", value=" + value + '}';
    }

}