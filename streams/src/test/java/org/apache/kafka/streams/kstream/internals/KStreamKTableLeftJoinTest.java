/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValue;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.test.KStreamTestDriver;
import org.apache.kafka.test.MockProcessorSupplier;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class KStreamKTableLeftJoinTest {

    private String topic1 = "topic1";
    private String topic2 = "topic2";

    private IntegerSerializer keySerializer = new IntegerSerializer();
    private StringSerializer valSerializer = new StringSerializer();
    private IntegerDeserializer keyDeserializer = new IntegerDeserializer();
    private StringDeserializer valDeserializer = new StringDeserializer();

    private ValueJoiner<String, String, String> joiner = new ValueJoiner<String, String, String>() {
        @Override
        public String apply(String value1, String value2) {
            return value1 + "+" + value2;
        }
    };

    private KeyValueMapper<Integer, String, KeyValue<Integer, String>> keyValueMapper =
        new KeyValueMapper<Integer, String, KeyValue<Integer, String>>() {
            @Override
            public KeyValue<Integer, String> apply(Integer key, String value) {
                return KeyValue.pair(key, value);
            }
        };

    @Test
    public void testJoin() throws Exception {
        File baseDir = Files.createTempDirectory("test").toFile();
        try {

            KStreamBuilder builder = new KStreamBuilder();

            final int[] expectedKeys = new int[]{0, 1, 2, 3};

            KStream<Integer, String> stream;
            KTable<Integer, String> table;
            MockProcessorSupplier<Integer, String> processor;

            processor = new MockProcessorSupplier<>();
            stream = builder.stream(keyDeserializer, valDeserializer, topic1);
            table = builder.table(keySerializer, valSerializer, keyDeserializer, valDeserializer, topic2);
            stream.leftJoin(table, joiner).process(processor);

            Collection<Set<String>> copartitionGroups = builder.copartitionGroups();

            assertEquals(1, copartitionGroups.size());
            assertEquals(new HashSet<>(Arrays.asList(topic1, topic2)), copartitionGroups.iterator().next());

            KStreamTestDriver driver = new KStreamTestDriver(builder, baseDir);
            driver.setTime(0L);

            // push two items to the primary stream. the other table is empty

            for (int i = 0; i < 2; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+null", "1:X1+null");

            // push two items to the other stream. this should not produce any item.

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], "Y" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // push all four items to the primary stream. this should produce four items.

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+Y0", "1:X1+Y1", "2:X2+null", "3:X3+null");

            // push all items to the other stream. this should not produce any item
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // push all four items to the primary stream. this should produce four items.

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "1:X1+YY1", "2:X2+YY2", "3:X3+YY3");

            // push two items with null to the other stream as deletes. this should not produce any item.

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], null);
            }

            processor.checkAndClearResult();

            // push all four items to the primary stream. this should produce four items.

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+null", "1:XX1+null", "2:XX2+YY2", "3:XX3+YY3");

        } finally {
            Utils.delete(baseDir);
        }
    }

    @Test(expected = KafkaException.class)
    public void testNotJoinable() {
        KStreamBuilder builder = new KStreamBuilder();

        KStream<Integer, String> stream;
        KTable<Integer, String> table;
        MockProcessorSupplier<Integer, String> processor;

        processor = new MockProcessorSupplier<>();
        stream = builder.stream(keyDeserializer, valDeserializer, topic1).map(keyValueMapper);
        table = builder.table(keySerializer, valSerializer, keyDeserializer, valDeserializer, topic2);

        stream.leftJoin(table, joiner).process(processor);
    }

}
