/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.worker;

import org.gradle.api.internal.tasks.testing.*;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.id.CompositeIdGenerator;
import org.gradle.messaging.remote.internal.Message;
import org.gradle.messaging.serialize.*;
import org.gradle.messaging.serialize.kryo.StatefulSerializer;

import java.util.HashMap;
import java.util.Map;

public class TestEventSerializer implements StatefulSerializer<Object[]> {
    private final Serializer<Object> paramSerializer = new ParamSerializer();

    public ObjectReader<Object[]> newReader(final Decoder decoder) {
        return new ObjectReader<Object[]>() {
            public Object[] read() throws Exception {
                int count = decoder.readSmallInt();
                Object[] params = new Object[count];
                for (int i = 0; i < params.length; i++) {
                    params[i] = paramSerializer.read(decoder);
                }
                return params;
            }
        };
    }

    public ObjectWriter<Object[]> newWriter(final Encoder encoder) {
        return new ObjectWriter<Object[]>() {
            public void write(Object[] value) throws Exception {
                encoder.writeSmallInt(value.length);
                for (int i = 0; i < value.length; i++) {
                    paramSerializer.write(encoder, value[i]);
                }
            }
        };
    }

    private static class TypeInfo {
        final byte tag;
        final Serializer serializer;

        private TypeInfo(byte tag, Serializer serializer) {
            this.tag = tag;
            this.serializer = serializer;
        }
    }

    private static class ParamSerializer implements Serializer<Object> {
        private final Map<Class<?>, TypeInfo> serializersByType = new HashMap<Class<?>, TypeInfo>();
        private final Map<Byte, TypeInfo> serializersByTag = new HashMap<Byte, TypeInfo>();

        private ParamSerializer() {
            add(DefaultTestClassRunInfo.class, new DefaultTestClassRunInfoSerializer());
            add(CompositeIdGenerator.CompositeId.class, new IdSerializer());
            add(DefaultTestSuiteDescriptor.class, new DefaultTestSuiteDescriptorSerializer());
            add(WorkerTestClassProcessor.WorkerTestSuiteDescriptor.class, new WorkerTestSuiteDescriptorSerializer());
            add(DefaultTestClassDescriptor.class, new DefaultTestClassDescriptorSerializer());
            add(DefaultTestMethodDescriptor.class, new DefaultTestMethodDescriptorSerializer());
            add(DefaultTestDescriptor.class, new DefaultTestDescriptorSerializer());
            add(TestStartEvent.class, new TestStartEventSerializer());
            add(TestCompleteEvent.class, new TestCompleteEventSerializer());
            add(DefaultTestOutputEvent.class, new DefaultTestOutputEventSerializer());
            add(Throwable.class, new ThrowableSerializer());
        }

        private <T> void add(Class<T> type, Serializer<T> serializer) {
            TypeInfo typeInfo = new TypeInfo((byte) serializersByTag.size(), serializer);
            serializersByType.put(type, typeInfo);
            serializersByTag.put(typeInfo.tag, typeInfo);
        }

        public Object read(Decoder decoder) throws Exception {
            byte tag = decoder.readByte();
            TypeInfo typeInfo = serializersByTag.get(tag);
            return typeInfo.serializer.read(decoder);
        }

        public void write(Encoder encoder, Object value) throws Exception {
            Class<?> targetType = value instanceof Throwable ? Throwable.class : value.getClass();
            TypeInfo typeInfo = serializersByType.get(targetType);
            if (typeInfo == null) {
                throw new IllegalArgumentException(String.format("Don't know how to serialize an object of type %s.", value.getClass().getName()));
            }
            encoder.writeByte(typeInfo.tag);
            typeInfo.serializer.write(encoder, value);
        }
    }

    private static class EnumSerializer<T extends Enum> implements Serializer<T> {
        private final Class<T> type;

        private EnumSerializer(Class<T> type) {
            this.type = type;
            if (type.getEnumConstants().length > Byte.MAX_VALUE) {
                throw new IllegalArgumentException(String.format("Too many constants for enum %s", type.getName()));
            }
        }

        public T read(Decoder decoder) throws Exception {
            return type.getEnumConstants()[decoder.readByte()];
        }

        public void write(Encoder encoder, T value) throws Exception {
            encoder.writeByte((byte) value.ordinal());
        }
    }

    private static class NullableSerializer<T> implements Serializer<T> {
        private final Serializer<T> serializer;

        private NullableSerializer(Serializer<T> serializer) {
            this.serializer = serializer;
        }

        public T read(Decoder decoder) throws Exception {
            if (!decoder.readBoolean()) {
                return null;
            }
            return serializer.read(decoder);
        }

        public void write(Encoder encoder, T value) throws Exception {
            encoder.writeBoolean(value != null);
            if (value != null) {
                serializer.write(encoder, value);
            }
        }
    }

    private static class ThrowableSerializer implements Serializer<Throwable> {
        public Throwable read(Decoder decoder) throws Exception {
            return (Throwable) Message.receive(decoder.getInputStream(), getClass().getClassLoader());
        }

        public void write(Encoder encoder, Throwable value) throws Exception {
            Message.send(value, encoder.getOutputStream());
        }
    }

    private static class IdSerializer implements Serializer<CompositeIdGenerator.CompositeId> {
        public CompositeIdGenerator.CompositeId read(Decoder decoder) throws Exception {
            return new CompositeIdGenerator.CompositeId(decoder.readLong(), decoder.readLong());
        }

        public void write(Encoder encoder, CompositeIdGenerator.CompositeId value) throws Exception {
            encoder.writeLong((Long) value.getScope());
            encoder.writeLong((Long) value.getId());
        }
    }

    private static class DefaultTestClassRunInfoSerializer implements Serializer<DefaultTestClassRunInfo> {
        public DefaultTestClassRunInfo read(Decoder decoder) throws Exception {
            return new DefaultTestClassRunInfo(decoder.readString());
        }

        public void write(Encoder encoder, DefaultTestClassRunInfo value) throws Exception {
            encoder.writeString(value.getTestClassName());
        }
    }

    private static class TestStartEventSerializer implements Serializer<TestStartEvent> {
        final Serializer<CompositeIdGenerator.CompositeId> idSerializer = new NullableSerializer<CompositeIdGenerator.CompositeId>(new IdSerializer());

        public TestStartEvent read(Decoder decoder) throws Exception {
            long time = decoder.readLong();
            Object id = idSerializer.read(decoder);
            return new TestStartEvent(time, id);
        }

        public void write(Encoder encoder, TestStartEvent value) throws Exception {
            encoder.writeLong(value.getStartTime());
            idSerializer.write(encoder, (CompositeIdGenerator.CompositeId) value.getParentId());
        }
    }

    private static class TestCompleteEventSerializer implements Serializer<TestCompleteEvent> {
        private final Serializer<TestResult.ResultType> typeSerializer = new NullableSerializer<TestResult.ResultType>(new EnumSerializer<TestResult.ResultType>(TestResult.ResultType.class));

        public TestCompleteEvent read(Decoder decoder) throws Exception {
            long endTime = decoder.readLong();
            TestResult.ResultType result = typeSerializer.read(decoder);
            return new TestCompleteEvent(endTime, result);
        }

        public void write(Encoder encoder, TestCompleteEvent value) throws Exception {
            encoder.writeLong(value.getEndTime());
            typeSerializer.write(encoder, value.getResultType());
        }
    }

    private static class DefaultTestOutputEventSerializer implements Serializer<DefaultTestOutputEvent> {
        private final Serializer<TestOutputEvent.Destination> destinationSerializer = new EnumSerializer<TestOutputEvent.Destination>(TestOutputEvent.Destination.class);
        
        public DefaultTestOutputEvent read(Decoder decoder) throws Exception {
            TestOutputEvent.Destination destination = destinationSerializer.read(decoder);
            String message = decoder.readString();
            return new DefaultTestOutputEvent(destination, message);
        }

        public void write(Encoder encoder, DefaultTestOutputEvent value) throws Exception {
            destinationSerializer.write(encoder, value.getDestination());
            encoder.writeString(value.getMessage());
        }
    }

    private static class DefaultTestSuiteDescriptorSerializer implements Serializer<DefaultTestSuiteDescriptor> {
        final Serializer<CompositeIdGenerator.CompositeId> idSerializer = new IdSerializer();

        public DefaultTestSuiteDescriptor read(Decoder decoder) throws Exception {
            Object id = idSerializer.read(decoder);
            String name = decoder.readString();
            return new DefaultTestSuiteDescriptor(id, name);
        }

        public void write(Encoder encoder, DefaultTestSuiteDescriptor value) throws Exception {
            idSerializer.write(encoder, (CompositeIdGenerator.CompositeId) value.getId());
            encoder.writeString(value.getName());
        }
    }

    private static class WorkerTestSuiteDescriptorSerializer implements Serializer<WorkerTestClassProcessor.WorkerTestSuiteDescriptor> {
        final Serializer<CompositeIdGenerator.CompositeId> idSerializer = new IdSerializer();

        public WorkerTestClassProcessor.WorkerTestSuiteDescriptor read(Decoder decoder) throws Exception {
            Object id = idSerializer.read(decoder);
            String name = decoder.readString();
            return new WorkerTestClassProcessor.WorkerTestSuiteDescriptor(id, name);
        }

        public void write(Encoder encoder, WorkerTestClassProcessor.WorkerTestSuiteDescriptor value) throws Exception {
            idSerializer.write(encoder, (CompositeIdGenerator.CompositeId) value.getId());
            encoder.writeString(value.getName());
        }
    }

    private static class DefaultTestClassDescriptorSerializer implements Serializer<DefaultTestClassDescriptor> {
        final Serializer<CompositeIdGenerator.CompositeId> idSerializer = new IdSerializer();

        public DefaultTestClassDescriptor read(Decoder decoder) throws Exception {
            Object id = idSerializer.read(decoder);
            String name = decoder.readString();
            return new DefaultTestClassDescriptor(id, name);
        }

        public void write(Encoder encoder, DefaultTestClassDescriptor value) throws Exception {
            idSerializer.write(encoder, (CompositeIdGenerator.CompositeId) value.getId());
            encoder.writeString(value.getName());
        }
    }

    private static class DefaultTestDescriptorSerializer implements Serializer<DefaultTestDescriptor> {
        final Serializer<CompositeIdGenerator.CompositeId> idSerializer = new IdSerializer();

        public DefaultTestDescriptor read(Decoder decoder) throws Exception {
            Object id = idSerializer.read(decoder);
            String className = decoder.readString();
            String name = decoder.readString();
            return new DefaultTestDescriptor(id, className, name);
        }

        public void write(Encoder encoder, DefaultTestDescriptor value) throws Exception {
            idSerializer.write(encoder, (CompositeIdGenerator.CompositeId) value.getId());
            encoder.writeString(value.getClassName());
            encoder.writeString(value.getName());
        }
    }

    private static class DefaultTestMethodDescriptorSerializer implements Serializer<DefaultTestMethodDescriptor> {
        final Serializer<CompositeIdGenerator.CompositeId> idSerializer = new IdSerializer();

        public DefaultTestMethodDescriptor read(Decoder decoder) throws Exception {
            Object id = idSerializer.read(decoder);
            String className = decoder.readString();
            String name = decoder.readString();
            return new DefaultTestMethodDescriptor(id, className, name);
        }

        public void write(Encoder encoder, DefaultTestMethodDescriptor value) throws Exception {
            idSerializer.write(encoder, (CompositeIdGenerator.CompositeId) value.getId());
            encoder.writeString(value.getClassName());
            encoder.writeString(value.getName());
        }
    }
}
