package org.apache.nifi.processors.pravega;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.controller.NodeTypeProvider;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractPravegaProcessor extends AbstractSessionFactoryProcessor {
    static final Validator CONTROLLER_VALIDATOR = new Validator() {
        @Override
        public ValidationResult validate(String subject, String input, ValidationContext context) {
            try{
                final URI controllerURI = new URI(input);
            } catch (URISyntaxException e) {
                return new ValidationResult.Builder().subject(subject).valid(false).explanation("it is not valid URI syntax.").build();
            }
            return new ValidationResult.Builder().subject(subject).valid(true).build();
        }
    };
    static final PropertyDescriptor PROP_CONTROLLER = new PropertyDescriptor.Builder()
            .name("controller")
            .displayName("Pravega Controller URI")
            .description("The URI of the Pravega controller (e.g. tcp://localhost:9090).")
            .required(true)
            .addValidator(CONTROLLER_VALIDATOR)
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
            .build();ComponentLog logger;

    static List<PropertyDescriptor> getAbstractPropertyDescriptors(){
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PROP_CONTROLLER);
        descriptors.add(PROP_SCOPE);
        descriptors.add(PROP_STREAM);
        return descriptors;
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        logger = getLogger();
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSessionFactory sessionFactory) throws ProcessException {
        final ProcessSession session = sessionFactory.createSession();
        try {
            onTrigger(context, sessionFactory, session);
            session.commit();
        } catch (final Throwable t) {
            session.rollback(true);
            throw t;
        }
    }

    public abstract void onTrigger(final ProcessContext context, final ProcessSessionFactory sessionFactory, final ProcessSession session) throws ProcessException;

    public boolean isPrimaryNode() {
        final NodeTypeProvider provider = getNodeTypeProvider();
        final boolean isClustered = provider.isClustered();
        final boolean isPrimary = provider.isPrimary();
        final boolean isPrimaryNode = (!isClustered) || isPrimary;
        logger.debug("isPrimaryNode: isClustered={}, isPrimary={}, isPrimaryNode={}",
                new Object[]{isClustered, isPrimary, isPrimaryNode});
        return isPrimaryNode;
    }

    /**
     * Builds transit URI for provenance event. The transit URI will be in the form of
     * tcp://localhost:9090/scope/stream.
     */
    static String buildTransitURI(String controller, String scope, String streamName) {
        return String.format("%s/%s/%s", controller, scope, streamName);
    }

    // Based on https://stackoverflow.com/a/13006907/5890553.
    public static String dumpByteArray(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 3);
        for (byte b: a)
            sb.append(String.format("%02x ", b));
        return sb.toString();
    }
}
