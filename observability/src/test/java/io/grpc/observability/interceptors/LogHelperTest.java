/*
 * Copyright 2022 The gRPC Authors
 *
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

package io.grpc.observability.interceptors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.Status;
import io.grpc.internal.TimeProvider;
import io.grpc.observability.interceptors.LogHelper.PayloadBuilder;
import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.Address;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.observabilitylog.v1.GrpcLogRecord.LogLevel;
import io.grpc.observabilitylog.v1.GrpcLogRecord.MetadataEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link LogHelper}.
 */
@RunWith(JUnit4.class)
public class LogHelperTest {

  private static final Charset US_ASCII = Charset.forName("US-ASCII");
  public static final Marshaller<byte[]> BYTEARRAY_MARSHALLER = new ByteArrayMarshaller();
  private static final String DATA_A = "aaaaaaaaa";
  private static final String DATA_B = "bbbbbbbbb";
  private static final String DATA_C = "ccccccccc";
  private static final Metadata.Key<String> KEY_A =
      Metadata.Key.of("a", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> KEY_B =
      Metadata.Key.of("b", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> KEY_C =
      Metadata.Key.of("c", Metadata.ASCII_STRING_MARSHALLER);
  private static final MetadataEntry ENTRY_A =
      MetadataEntry
          .newBuilder()
          .setKey(KEY_A.name())
          .setValue(ByteString.copyFrom(DATA_A.getBytes(US_ASCII)))
          .build();
  private static final MetadataEntry ENTRY_B =
      MetadataEntry
          .newBuilder()
          .setKey(KEY_B.name())
          .setValue(ByteString.copyFrom(DATA_B.getBytes(US_ASCII)))
          .build();
  private static final MetadataEntry ENTRY_C =
      MetadataEntry
          .newBuilder()
          .setKey(KEY_C.name())
          .setValue(ByteString.copyFrom(DATA_C.getBytes(US_ASCII)))
          .build();


  private final Metadata nonEmptyMetadata = new Metadata();
  private final int nonEmptyMetadataSize = 30;
  private final Sink sink = mock(GcpLogSink.class);
  private final Timestamp timestamp
      = Timestamp.newBuilder().setSeconds(9876).setNanos(54321).build();
  private final TimeProvider timeProvider = new TimeProvider() {
    @Override
    public long currentTimeNanos() {
      return TimeUnit.SECONDS.toNanos(9876) + 54321;
    }
  };
  private final LogHelper logHelper =
      new LogHelper(
          sink,
          timeProvider);

  @Before
  public void setUp() throws Exception {
    nonEmptyMetadata.put(KEY_A, DATA_A);
    nonEmptyMetadata.put(KEY_B, DATA_B);
    nonEmptyMetadata.put(KEY_C, DATA_C);
  }

  @Test
  public void socketToProto_ipv4() throws Exception {
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress socketAddress = new InetSocketAddress(address, port);
    assertEquals(
        Address
            .newBuilder()
            .setType(Address.Type.TYPE_IPV4)
            .setAddress("127.0.0.1")
            .setIpPort(12345)
            .build(),
        LogHelper.socketAddressToProto(socketAddress));
  }

  @Test
  public void socketToProto_ipv6() throws Exception {
    // this is a ipv6 link local address
    InetAddress address = InetAddress.getByName("2001:db8:0:0:0:0:2:1");
    int port = 12345;
    InetSocketAddress socketAddress = new InetSocketAddress(address, port);
    assertEquals(
        Address
            .newBuilder()
            .setType(Address.Type.TYPE_IPV6)
            .setAddress("2001:db8::2:1") // RFC 5952 section 4: ipv6 canonical form required
            .setIpPort(12345)
            .build(),
        LogHelper.socketAddressToProto(socketAddress));
  }

  @Test
  public void socketToProto_unknown() throws Exception {
    SocketAddress unknownSocket = new SocketAddress() {
      @Override
      public String toString() {
        return "some-socket-address";
      }
    };
    assertEquals(
        Address.newBuilder()
            .setType(Address.Type.TYPE_UNKNOWN)
            .setAddress("some-socket-address")
            .build(),
        LogHelper.socketAddressToProto(unknownSocket));
  }

  @Test
  public void metadataToProto_empty() throws Exception {
    assertEquals(
        GrpcLogRecord.newBuilder()
            .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
            .setMetadata(
                GrpcLogRecord.Metadata.getDefaultInstance())
            .build(),
        metadataToProtoTestHelper(
            EventType.GRPC_CALL_REQUEST_HEADER, new Metadata()));
  }

  @Test
  public void metadataToProto() throws Exception {
    assertEquals(
        GrpcLogRecord.newBuilder()
            .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
            .setMetadata(
                GrpcLogRecord.Metadata
                    .newBuilder()
                    .addEntry(ENTRY_A)
                    .addEntry(ENTRY_B)
                    .addEntry(ENTRY_C)
                    .build())
            .setPayloadSize(nonEmptyMetadataSize)
            .build(),
        metadataToProtoTestHelper(
            EventType.GRPC_CALL_REQUEST_HEADER, nonEmptyMetadata));
  }

  @Test
  public void logRequestHeader() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String authority = "authority";
    Duration timeout = Durations.fromMillis(1234);
    String rpcId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress peerAddress = new InetSocketAddress(address, port);

    GrpcLogRecord.Builder builder =
        metadataToProtoTestHelper(EventType.GRPC_CALL_REQUEST_HEADER, nonEmptyMetadata)
            .toBuilder()
            .setTimestamp(timestamp)
            .setSequenceId(seqId)
            .setServiceName(serviceName)
            .setMethodName(methodName)
            .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
            .setEventLogger(EventLogger.LOGGER_CLIENT)
            .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
            .setRpcId(rpcId);
    builder.setAuthority(authority)
        .setTimeout(timeout);
    GrpcLogRecord base = builder.build();

    // logged on client
    {
      logHelper.logRequestHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          timeout,
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          null);
      verify(sink).write(base);
    }

    // logged on server
    {
      logHelper.logRequestHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          timeout,
          nonEmptyMetadata,
          EventLogger.LOGGER_SERVER,
          rpcId,
          peerAddress);
      verify(sink).write(
          base.toBuilder()
              .setPeerAddress(LogHelper.socketAddressToProto(peerAddress))
              .setEventLogger(EventLogger.LOGGER_SERVER)
              .build());
    }

    // timeout is null
    {
      logHelper.logRequestHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          null,
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          null);
      verify(sink).write(
          base.toBuilder()
              .clearTimeout()
              .build());
    }

    // peerAddress is not null (error on client)
    try {
      logHelper.logRequestHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          timeout,
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          peerAddress);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("peerAddress can only be specified by server");
    }
  }

  @Test
  public void logResponseHeader() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String rpcId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress peerAddress = new InetSocketAddress(address, port);

    GrpcLogRecord.Builder builder =
        metadataToProtoTestHelper(EventType.GRPC_CALL_RESPONSE_HEADER, nonEmptyMetadata)
            .toBuilder()
            .setTimestamp(timestamp)
            .setSequenceId(seqId)
            .setServiceName(serviceName)
            .setMethodName(methodName)
            .setEventType(EventType.GRPC_CALL_RESPONSE_HEADER)
            .setEventLogger(EventLogger.LOGGER_CLIENT)
            .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
            .setRpcId(rpcId);
    builder.setPeerAddress(LogHelper.socketAddressToProto(peerAddress));
    GrpcLogRecord base = builder.build();

    // logged on client
    {
      logHelper.logResponseHeader(
          seqId,
          serviceName,
          methodName,
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          peerAddress);
      verify(sink).write(base);
    }

    // logged on server
    {
      logHelper.logResponseHeader(
          seqId,
          serviceName,
          methodName,
          nonEmptyMetadata,
          EventLogger.LOGGER_SERVER,
          rpcId,
          null);
      verify(sink).write(
          base.toBuilder()
              .setEventLogger(EventLogger.LOGGER_SERVER)
              .clearPeerAddress()
              .build());
    }

    // peerAddress is not null (error on server)
    try {
      logHelper.logResponseHeader(
          seqId,
          serviceName,
          methodName,
          nonEmptyMetadata,
          EventLogger.LOGGER_SERVER,
          rpcId,
          peerAddress);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat()
          .contains("peerAddress can only be specified for client");
    }
  }

  @Test
  public void logTrailer() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String rpcId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress peerAddress = new InetSocketAddress(address, port);
    Status statusDescription = Status.INTERNAL.withDescription("test description");

    GrpcLogRecord.Builder builder =
        metadataToProtoTestHelper(EventType.GRPC_CALL_RESPONSE_HEADER, nonEmptyMetadata)
            .toBuilder()
            .setTimestamp(timestamp)
            .setSequenceId(seqId)
            .setServiceName(serviceName)
            .setMethodName(methodName)
            .setEventType(EventType.GRPC_CALL_TRAILER)
            .setEventLogger(EventLogger.LOGGER_CLIENT)
            .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
            .setStatusCode(Status.INTERNAL.getCode().value())
            .setStatusMessage("test description")
            .setRpcId(rpcId);
    builder.setPeerAddress(LogHelper.socketAddressToProto(peerAddress));
    GrpcLogRecord base = builder.build();

    // logged on client
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          statusDescription,
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          peerAddress);
      verify(sink).write(base);
    }

    // logged on server
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          statusDescription,
          nonEmptyMetadata,
          EventLogger.LOGGER_SERVER,
          rpcId,
          null);
      verify(sink).write(
          base.toBuilder()
              .clearPeerAddress()
              .setEventLogger(EventLogger.LOGGER_SERVER)
              .build());
    }

    // peer address is null
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          statusDescription,
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          null);
      verify(sink).write(
          base.toBuilder()
              .clearPeerAddress()
              .build());
    }

    // status description is null
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          statusDescription.getCode().toStatus(),
          nonEmptyMetadata,
          EventLogger.LOGGER_CLIENT,
          rpcId,
          peerAddress);
      verify(sink).write(
          base.toBuilder()
              .clearStatusMessage()
              .build());
    }
  }

  @Test
  public void logRpcMessage() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String rpcId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    byte[] message = new byte[100];

    GrpcLogRecord.Builder builder = messageTestHelper(message)
        .toBuilder()
        .setTimestamp(timestamp)
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setEventType(EventType.GRPC_CALL_REQUEST_MESSAGE)
        .setEventLogger(EventLogger.LOGGER_CLIENT)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setRpcId(rpcId);
    GrpcLogRecord base = builder.build();
    // request message
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          EventType.GRPC_CALL_REQUEST_MESSAGE,
          message,
          EventLogger.LOGGER_CLIENT,
          rpcId);
      verify(sink).write(base);
    }
    // response message, logged on client
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          EventType.GRPC_CALL_RESPONSE_MESSAGE,
          message,
          EventLogger.LOGGER_CLIENT,
          rpcId);
      verify(sink).write(
          base.toBuilder()
              .setEventType(EventType.GRPC_CALL_RESPONSE_MESSAGE)
              .build());
    }
    // request message, logged on server
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          EventType.GRPC_CALL_REQUEST_MESSAGE,
          message,
          EventLogger.LOGGER_SERVER,
          rpcId);
      verify(sink).write(
          base.toBuilder()
              .setEventLogger(EventLogger.LOGGER_SERVER)
              .build());
    }
    // response message, logged on server
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          EventType.GRPC_CALL_RESPONSE_MESSAGE,
          message,
          EventLogger.LOGGER_SERVER,
          rpcId);
      verify(sink).write(
          base.toBuilder()
              .setEventType(EventType.GRPC_CALL_RESPONSE_MESSAGE)
              .setEventLogger(EventLogger.LOGGER_SERVER)
              .build());
    }
  }

  @Test
  public void getPeerAddressTest() throws Exception {
    SocketAddress peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
    assertNull(LogHelper.getPeerAddress(Attributes.EMPTY));
    assertSame(
        peer,
        LogHelper.getPeerAddress(
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build()));
  }

  private static GrpcLogRecord metadataToProtoTestHelper(
      EventType type, Metadata metadata) {
    GrpcLogRecord.Builder builder = GrpcLogRecord.newBuilder();
    PayloadBuilder<GrpcLogRecord.Metadata.Builder> pair
        = LogHelper.createMetadataProto(metadata);
    builder.setMetadata(pair.payload);
    builder.setPayloadSize(pair.size);
    builder.setEventType(type);
    return builder.build();
  }

  private static GrpcLogRecord messageTestHelper(byte[] message) {
    GrpcLogRecord.Builder builder = GrpcLogRecord.newBuilder();
    PayloadBuilder<ByteString> pair
        = LogHelper.createMesageProto(message);
    builder.setMessage(pair.payload);
    builder.setPayloadSize(pair.size);
    return builder.build();
  }

  // Used only in tests
  // Copied from internal
  static final class ByteArrayMarshaller implements Marshaller<byte[]> {
    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return parseHelper(stream);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private byte[] parseHelper(InputStream stream) throws IOException {
      try {
        return IoUtils.toByteArray(stream);
      } finally {
        stream.close();
      }
    }
  }

  // Copied from internal
  static final class IoUtils {
    /** maximum buffer to be read is 16 KB. */
    private static final int MAX_BUFFER_LENGTH = 16384;

    /** Returns the byte array. */
    public static byte[] toByteArray(InputStream in) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      copy(in, out);
      return out.toByteArray();
    }

    /** Copies the data from input stream to output stream. */
    public static long copy(InputStream from, OutputStream to) throws IOException {
      // Copied from guava com.google.common.io.ByteStreams because its API is unstable (beta)
      checkNotNull(from);
      checkNotNull(to);
      byte[] buf = new byte[MAX_BUFFER_LENGTH];
      long total = 0;
      while (true) {
        int r = from.read(buf);
        if (r == -1) {
          break;
        }
        to.write(buf, 0, r);
        total += r;
      }
      return total;
    }
  }
}
