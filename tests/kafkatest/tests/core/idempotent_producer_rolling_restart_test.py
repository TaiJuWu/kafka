# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from ducktape.mark import matrix
from ducktape.mark.resource import cluster

from kafkatest.services.kafka import KafkaService, quorum
from kafkatest.services.verifiable_producer import VerifiableProducer
from kafkatest.services.console_consumer import ConsoleConsumer
from kafkatest.tests.produce_consume_validate import ProduceConsumeValidateTest
from kafkatest.utils import is_int


class IdempotentProducerRollingRestartTest(ProduceConsumeValidateTest):
    """
    Restart all brokers cleanly one after another, a few times, while an idempotent producer writes with acks=all,
    and check that no record is lost or written twice.

    With min.insync.replicas equal to the replication factor, a write that the leader has appended fails with
    NOT_ENOUGH_REPLICAS_AFTER_APPEND while a replica is down, so the producer keeps retrying records that are already
    in the log until the replica is back. When the leadership moves to a broker that restarted in the meantime, the
    retries go there.
    """

    def __init__(self, test_context):
        """:type test_context: ducktape.tests.test.TestContext"""
        super(IdempotentProducerRollingRestartTest, self).__init__(test_context=test_context)

        self.topic = "test_topic"
        self.num_partitions = 20
        self.kafka = KafkaService(test_context, num_nodes=3, zk=None,
                                  topics={self.topic: {
                                      "partitions": self.num_partitions,
                                      "replication-factor": 3,
                                      "configs": {"min.insync.replicas": 3}
                                  }},
                                  controller_num_nodes_override=1)
        self.num_producers = 1
        self.num_consumers = 1
        self.num_rolling_restarts = 3

    def min_cluster_size(self):
        # Override this since we're adding services outside of the constructor
        return super(IdempotentProducerRollingRestartTest, self).min_cluster_size() + self.num_producers + self.num_consumers

    def rolling_restarts(self):
        for i in range(self.num_rolling_restarts):
            self.logger.info("Rolling restart %d of %d" % (i + 1, self.num_rolling_restarts))
            self.kafka.restart_cluster(clean_shutdown=True)

    @cluster(num_nodes=6)
    @matrix(metadata_quorum=[quorum.isolated_kraft])
    def test_rolling_restart(self, metadata_quorum):
        self.kafka.start()
        self.producer = VerifiableProducer(self.test_context, self.num_producers,
                                           self.kafka, self.topic,
                                           throughput=10000,
                                           enable_idempotence=True,
                                           repeating_keys=100)
        self.consumer = ConsoleConsumer(self.test_context, self.num_consumers,
                                        self.kafka, self.topic,
                                        consumer_timeout_ms=60000,
                                        message_validator=is_int)

        self.enable_idempotence = True
        self.run_produce_consume_validate(core_test_action=self.rolling_restarts)
