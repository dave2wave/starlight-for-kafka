/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Transaction test.
 */
@Slf4j
public class IdempotentProducerTest extends KopProtocolHandlerTestBase {

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        this.conf.setTransactionCoordinatorEnabled(true);
        super.internalSetup();
        log.info("success internal setup");
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @DataProvider(name = "produceConfigProvider")
    protected static Object[][] produceConfigProvider() {
        // isBatch
        return new Object[][]{
                {true},
                {false}
        };
    }

    @Test
    public void testIdempotentProducer() throws PulsarAdminException {
        String topic = "testIdempotentProducer";
        admin.topics().createPartitionedTopic(topic, 1);
        int maxMessageNum = 1000;
        Properties producerProperties = newKafkaProducerProperties();
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        producerProperties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);

        @Cleanup
        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties);

        for (int i = 0; i < maxMessageNum; i++) {
            producer.send(new ProducerRecord<>(topic, "test" + i));
        }
        producer.flush();

        @Cleanup
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(newKafkaConsumerProperties());
        consumer.subscribe(Collections.singleton(topic));
        int i = 0;
        while (i < maxMessageNum) {
            ConsumerRecords<String, String> messages = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> message : messages) {
                assertEquals("test" + i, message.value());
                i++;
            }
        }
        assertEquals(maxMessageNum, i);
    }

}