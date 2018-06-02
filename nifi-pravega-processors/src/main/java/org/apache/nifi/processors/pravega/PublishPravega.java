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
package org.apache.nifi.processors.pravega;

import io.pravega.client.ClientFactory;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.Transaction;
import io.pravega.client.stream.TxnFailedException;
import io.pravega.client.stream.impl.ByteArraySerializer;
import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.FlowFileFilters;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.stream.io.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tags({"Pravega", "Put", "Send", "Message"})
@CapabilityDescription("Sends the contents of a FlowFile as a message to Pravega.")
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@EventDriven
@SupportsBatching
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="pravega.routing.key", description="The Pravega routing key")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class PublishPravega extends AbstractProcessor {
    protected ComponentLog logger;
    protected EventStreamWriter<byte[]> cachedWriter;
    protected ClientFactory cachedClientFactory;

    static final PropertyDescriptor PROP_CONTROLLER = new PropertyDescriptor.Builder()
            .name("controller")
            .displayName("Pravega Controller URI")
            .description("The URI of the Pravega controller (e.g. tcp://localhost:9090).")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    static final PropertyDescriptor PROP_SCOPE = new PropertyDescriptor.Builder()
            .name("scope")
            .displayName("Pravega Scope")
            .description("The name of the Pravega scope.")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    static final PropertyDescriptor PROP_STREAM = new PropertyDescriptor.Builder()
            .name("stream")
            .displayName("Pravega Stream")
            .description("The name of the Pravega stream.")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("FlowFiles for which all content was sent to Pravega.")
        .build();

    static final Relationship REL_FAILURE = new Relationship.Builder()
        .name("failure")
        .description("Any FlowFile that cannot be sent to Pravega will be routed to this Relationship.")
        .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        logger = getLogger();
        logger.info("PublishPravega.init");

        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PROP_CONTROLLER);
        descriptors.add(PROP_SCOPE);
        descriptors.add(PROP_STREAM);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnStopped
    public void onStop(final ProcessContext context) {
        synchronized (this) {
            logger.info("PublishPravega.onStop");
            if (cachedWriter != null) {
                cachedWriter.close();
                cachedWriter = null;
            }
            if (cachedClientFactory != null) {
                cachedClientFactory.close();
                cachedClientFactory = null;
            }
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        logger.info("PublishPravega.onTrigger: BEGIN");

        final List<FlowFile> flowFiles = session.get(FlowFileFilters.newSizeBasedFilter(250, DataUnit.KB, 500));
        if (flowFiles.isEmpty()) {
            return;
        }

        try {
            EventStreamWriter<byte[]> writer = getWriter(context);
            Transaction<byte[]> transaction = writer.beginTxn();

            for (final FlowFile flowFile : flowFiles) {
                if (!isScheduled()) {
                    // If stopped, re-queue FlowFile instead of sending it
                    session.transfer(flowFile);
                    continue;
                }

                String routingKey = flowFile.getAttribute("pravega.routing.key");
                if (routingKey == null) {
                    routingKey = "";
                }

                // Read FlowFile contents.
                final byte[] messageContent = new byte[(int) flowFile.getSize()];
                session.read(flowFile, new InputStreamCallback() {
                    @Override
                    public void process(final InputStream in) throws IOException {
                        StreamUtils.fillBuffer(in, messageContent, true);
                    }
                });

                logger.debug("routingKey={}, size={}, messageContent={}",
                        new Object[]{routingKey, flowFile.getSize(), new String(messageContent)});

                // Write to Pravega.
                transaction.writeEvent(routingKey, messageContent);
            }
            transaction.commit();
        }
        catch (TxnFailedException e) {
            logger.error(e.getMessage());
            // Transfer the FlowFiles to the failure relationship.
            // The user can choose to route this back to this processor for retry.
            session.transfer(flowFiles, REL_FAILURE);
            return;
        }

        // Transfer any successful FlowFiles.
        for (FlowFile success : flowFiles) {
            session.transfer(success, REL_SUCCESS);
        }
        // TODO: update provenance
        // TODO: log message count and rate
        logger.info("PublishPravega.onTrigger: END");
    }

    protected EventStreamWriter<byte[]> getWriter(ProcessContext context) {
        synchronized (this) {
            if (cachedWriter == null) {
                try {
                    URI controllerURI = new URI(context.getProperty(PROP_CONTROLLER).getValue());
                    String scope = context.getProperty(PROP_SCOPE).getValue();
                    String streamName = context.getProperty(PROP_STREAM).getValue();

                    // TODO: create stream only if property allows it.
                    StreamManager streamManager = StreamManager.create(controllerURI);
                    streamManager.createScope(scope);
                    StreamConfiguration streamConfig = StreamConfiguration.builder()
                            .scalingPolicy(ScalingPolicy.fixed(2))
                            .build();
                    streamManager.createStream(scope, streamName, streamConfig);
                    streamManager.close();

                    cachedClientFactory = ClientFactory.withScope(scope, controllerURI);
                    cachedWriter = cachedClientFactory.createEventWriter(
                            streamName,
                            new ByteArraySerializer(),
                            EventWriterConfig.builder().build());
                    return cachedWriter;
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    throw new RuntimeException(e);
                }
            } else {
                return cachedWriter;
            }
        }
    }
}
