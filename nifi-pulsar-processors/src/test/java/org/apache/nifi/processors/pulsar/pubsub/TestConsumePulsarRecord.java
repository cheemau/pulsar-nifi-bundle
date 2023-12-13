/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.pulsar.pubsub;

import static org.apache.nifi.processors.pulsar.pubsub.ConsumePulsarRecord.RECORD_READER;
import static org.apache.nifi.processors.pulsar.pubsub.ConsumePulsarRecord.RECORD_WRITER;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.processors.pulsar.AbstractPulsarConsumerProcessor;
import org.apache.nifi.processors.pulsar.AbstractPulsarProcessorTest;
import org.apache.nifi.processors.pulsar.pubsub.mocks.MockRecordParser;
import org.apache.nifi.processors.pulsar.pubsub.mocks.MockRecordWriter;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunners;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class TestConsumePulsarRecord extends AbstractPulsarProcessorTest<byte[]> {

    protected static String BAD_MSG = "Malformed message";
    protected static String MOCKED_MSG = "Mocked Message, 1";
    protected static String DEFAULT_TOPIC = "foo";
    protected static String DEFAULT_SUB = "bar";

    @Mock
    protected Message<GenericRecord> mockMessage;

    protected MockRecordParser readerService;
    protected MockRecordWriter writerService;

    @SuppressWarnings("unchecked")
	@Before
    public void setup() throws InitializationException {

        mockMessage = mock(Message.class);

        runner = TestRunners.newTestRunner(ConsumePulsarRecord.class);

        final String readerId = "record-reader";
        readerService = new MockRecordParser();
        readerService.addSchemaField("name", RecordFieldType.STRING);
        readerService.addSchemaField("age", RecordFieldType.INT);
        runner.addControllerService(readerId, readerService);
        runner.enableControllerService(readerService);

        final String writerId = "record-writer";
        writerService = new MockRecordWriter("name, age");
        runner.addControllerService(writerId, writerService);
        runner.enableControllerService(writerService);

        runner.setProperty(RECORD_READER, readerId);
        runner.setProperty(RECORD_WRITER, writerId);
        addPulsarClientService();
    }

    @Test
    public void validatePropertiesValidation() throws Exception {
        // Initially the processor won't be properly configured
        runner.assertNotValid();

        runner.setProperty(AbstractPulsarConsumerProcessor.TOPICS, "my-topic");
        runner.setProperty(AbstractPulsarConsumerProcessor.SUBSCRIPTION_NAME, "my-sub");
        runner.assertValid();
    }

    protected void doFailedParseHandlingTest(String msg, String topic, String sub, boolean async) throws Exception {
    	final String failingReaderId = "failing-record-reader";
    	readerService = new MockRecordParser(-1);
    	readerService.addSchemaField("name",  RecordFieldType.STRING);
    	readerService.addSchemaField("age",  RecordFieldType.INT);
    	runner.addControllerService(failingReaderId, readerService);
    	runner.enableControllerService(readerService);
    	
    	runner.setProperty(RECORD_READER, failingReaderId);
    	
    	runner.setProperty(ConsumePulsarRecord.ASYNC_ENABLED, Boolean.toString(async));
    	runner.setProperty(ConsumePulsarRecord.TOPICS, topic);
    	runner.setProperty(ConsumePulsarRecord.SUBSCRIPTION_NAME, sub);
    	runner.setProperty(ConsumePulsarRecord.CONSUMER_BATCH_SIZE, 2 + "");
    	runner.setProperty(ConsumePulsarRecord.MESSAGE_DEMARCATOR, "***");
    	runner.setProperty(ConsumePulsar.SUBSCRIPTION_TYPE, "Exclusive");
    	
    	if (async) {
    		runner.setProperty(ConsumePulsarRecord.MAX_WAIT_TIME, "5 sec");
    	} else {
    		runner.setProperty(ConsumePulsarRecord.MAX_WAIT_TIME, "0 sec");
    	}
    	
    	when(mockMessage.getData()).thenReturn(msg.getBytes());
        when(mockMessage.getTopicName()).thenReturn("foo");

        mockClientService.setMockMessage(mockMessage);
    	
    	runner.run();
    	runner.assertAllFlowFilesTransferred(ConsumePulsarRecord.REL_PARSE_FAILURE);
    	runner.assertQueueEmpty();
    	
    	List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(ConsumePulsarRecord.REL_PARSE_FAILURE);
    	assertEquals(1, flowFiles.size());
    	flowFiles.get(0).assertContentEquals(msg + "***" + msg);
    }
    
    protected List<MockFlowFile> sendMessages(String msg, boolean async, int iterations) throws PulsarClientException {
        return sendMessages(msg, DEFAULT_TOPIC, DEFAULT_SUB, async, iterations, 1);
    }

    protected List<MockFlowFile> sendMessages(String msg, boolean async, int iterations, int batchSize) throws PulsarClientException {
        return sendMessages(msg, async, iterations, batchSize, "Exclusive");
    }

    protected List<MockFlowFile> sendMessages(String msg, boolean async, int iterations, int batchSize, String subType) throws PulsarClientException {
        return sendMessages(msg, DEFAULT_TOPIC, DEFAULT_SUB, async, iterations, batchSize, subType);
    }

    protected List<MockFlowFile> sendMessages(String msg, String topic, String sub, boolean async, int iterations, int batchSize) throws PulsarClientException {
    	return sendMessages(msg, topic, sub, async, iterations, batchSize, "Exclusive");
    }
    
    protected List<MockFlowFile> sendMessages(String msg, String topic, String sub, boolean async, int iterations, int batchSize, String subType) throws PulsarClientException {
        when(mockMessage.getData()).thenReturn(msg.getBytes());
        when(mockMessage.getTopicName()).thenReturn(topic);
        mockClientService.setMockMessage(mockMessage);

        runner.setProperty(ConsumePulsarRecord.ASYNC_ENABLED, Boolean.toString(async));
        runner.setProperty(ConsumePulsarRecord.TOPICS, topic);
        runner.setProperty(ConsumePulsarRecord.SUBSCRIPTION_NAME, sub);
        runner.setProperty(ConsumePulsarRecord.CONSUMER_BATCH_SIZE, batchSize + "");
        runner.setProperty(ConsumePulsar.SUBSCRIPTION_TYPE, subType);
        
        if (async) {
          runner.setProperty(ConsumePulsarRecord.MAX_WAIT_TIME, "5 sec");
        } else {
          runner.setProperty(ConsumePulsarRecord.MAX_WAIT_TIME, "0 sec");
        }

        runner.run(iterations, true);
        runner.assertAllFlowFilesTransferred(ConsumePulsarRecord.REL_SUCCESS);

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(ConsumePulsarRecord.REL_SUCCESS);
        assertEquals(iterations, flowFiles.size());

        verify(mockClientService.getMockConsumer(), times(iterations * batchSize)).receive(0, TimeUnit.SECONDS);

        boolean shared = isSharedSubType(subType);
        
        if (shared) {
        	if (async) {
        		verify(mockClientService.getMockConsumer(), times(iterations * batchSize)).acknowledgeAsync(mockMessage);
        	} else {
        		verify(mockClientService.getMockConsumer(), times(iterations * batchSize)).acknowledge(mockMessage);
        	}
        }
        else {
        	if (async) {
        		verify(mockClientService.getMockConsumer(), times(iterations)).acknowledgeCumulativeAsync(mockMessage);
        	} else {
        		verify(mockClientService.getMockConsumer(), times(iterations)).acknowledgeCumulative(mockMessage);
        	}
        }
        
        return flowFiles;
    }

    protected void doMappedAttributesTest() throws PulsarClientException {
        when(mockMessage.getData()).thenReturn("A,10".getBytes())
           .thenReturn("B,10".getBytes())
           .thenReturn("C,10".getBytes())
           .thenReturn("D,10".getBytes());
        
        when(mockMessage.getProperty("prop")).thenReturn(null)
          .thenReturn(null)
          .thenReturn("val")
          .thenReturn("val");
        
        when(mockMessage.getKey()).thenReturn(null)
          .thenReturn(null)
          .thenReturn(null)
          .thenReturn("K");

        when(mockMessage.getTopicName()).thenReturn("foo");
        
        mockClientService.setMockMessage(mockMessage);

        runner.setProperty(ConsumePulsar.MAPPED_FLOWFILE_ATTRIBUTES, "prop,key=__KEY__");
        runner.setProperty(ConsumePulsarRecord.CONSUMER_BATCH_SIZE, "4");
        runner.setProperty(ConsumePulsarRecord.MESSAGE_DEMARCATOR, "===");
        runner.setProperty(ConsumePulsarRecord.TOPICS, "foo");
        runner.setProperty(ConsumePulsarRecord.SUBSCRIPTION_NAME, "bar");
        runner.setProperty(ConsumePulsarRecord.SUBSCRIPTION_TYPE, "Exclusive");

        runner.run();
        runner.assertAllFlowFilesTransferred(ConsumePulsarRecord.REL_SUCCESS);
        runner.assertQueueEmpty();

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(ConsumePulsarRecord.REL_SUCCESS);
        assertEquals(3, flowFiles.size());

        // first flow file should have A, second should have B
        flowFiles.get(0).assertAttributeNotExists("prop");
        flowFiles.get(0).assertAttributeNotExists("key");
        flowFiles.get(0).assertContentEquals("\"A\",\"10\"\n\"B\",\"10\"\n");
        flowFiles.get(1).assertAttributeEquals("prop", "val");
        flowFiles.get(1).assertAttributeNotExists("key");
        flowFiles.get(1).assertContentEquals("\"C\",\"10\"\n");
        flowFiles.get(2).assertAttributeEquals("prop", "val");
        flowFiles.get(2).assertAttributeEquals("key", "K");
        flowFiles.get(2).assertContentEquals("\"D\",\"10\"\n");
    }
}
