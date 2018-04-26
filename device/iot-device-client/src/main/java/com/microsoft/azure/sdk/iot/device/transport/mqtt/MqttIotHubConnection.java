// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.sdk.iot.device.transport.mqtt;

import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.*;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import static com.microsoft.azure.sdk.iot.device.MessageType.DEVICE_METHODS;
import static com.microsoft.azure.sdk.iot.device.MessageType.DEVICE_TWIN;

public class MqttIotHubConnection implements IotHubTransportConnection, MqttMessageListener
{
    /** The MQTT connection lock. */
    private final Object MQTT_CONNECTION_LOCK = new Object();

    private final DeviceClientConfig config;
    private IotHubConnectionStatus state = IotHubConnectionStatus.DISCONNECTED;

    private String iotHubUserName;
    private String iotHubUserPassword;
    private MqttConnection mqttConnection;

    //string constants
    private static final String WS_SSL_PREFIX = "wss://";
    private static final String WS_SSL_PORT_SUFFIX = ":443";

    private static final String WEBSOCKET_RAW_PATH = "/$iothub/websocket";
    private static final String WEBSOCKET_QUERY = "?iothub-no-client-cert=true";

    private static final String SSL_PREFIX = "ssl://";
    private static final String SSL_PORT_SUFFIX = ":8883";

    private static final String TWIN_API_VERSION = "api-version=2016-11-14";

    private IotHubListener listener;

    private CustomLogger logger;

    //Messaging clients
    private MqttMessaging deviceMessaging;
    private MqttDeviceTwin deviceTwin;
    private MqttDeviceMethod deviceMethod;

    private Map<IotHubTransportMessage, Integer> receivedMessagesToAcknowledge = new ConcurrentHashMap<>();

    /**
     * Constructs an instance from the given {@link DeviceClientConfig}
     * object.
     *
     * @param config the client configuration.
     */
    public MqttIotHubConnection(DeviceClientConfig config) throws IllegalArgumentException
    {
        synchronized (MQTT_CONNECTION_LOCK)
        {
            // Codes_SRS_MQTTIOTHUBCONNECTION_15_003: [The constructor shall throw a new IllegalArgumentException
            // if any of the parameters of the configuration is null or empty.]
            if (config == null)
            {
                throw new IllegalArgumentException("The DeviceClientConfig cannot be null.");
            }
            if (config.getIotHubHostname() == null || config.getIotHubHostname().length() == 0)
            {
                throw new IllegalArgumentException("hostName cannot be null or empty.");
            }
            if (config.getDeviceId() == null || config.getDeviceId().length() == 0)
            {
                throw new IllegalArgumentException("deviceID cannot be null or empty.");
            }
            if (config.getIotHubName() == null || config.getIotHubName().length() == 0)
            {
                throw new IllegalArgumentException("hubName cannot be null or empty.");
            }
            if (config.getAuthenticationType() == DeviceClientConfig.AuthType.SAS_TOKEN)
            {
                if (config.getIotHubConnectionString().getSharedAccessKey() == null || config.getIotHubConnectionString().getSharedAccessKey().isEmpty())
                {
                    if(config.getSasTokenAuthentication().getCurrentSasToken() == null || config.getSasTokenAuthentication().getCurrentSasToken().isEmpty())
                    {
                        //Codes_SRS_MQTTIOTHUBCONNECTION_34_020: [If the config has no shared access token, device key, or x509 certificates, this constructor shall throw an IllegalArgumentException.]
                        throw new IllegalArgumentException("Must have a deviceKey, a shared access token, or x509 certificate saved.");
                    }
                }
            }

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_001: [The constructor shall save the configuration.]
            this.config = config;
            this.deviceMessaging = null;
            this.deviceMethod = null;
            this.deviceTwin = null;
            this.logger = new CustomLogger(this.getClass());
        }
    }

    /**
     * Establishes a connection for the device and IoT Hub given in the client
     * configuration. If the connection is already open, the function shall do
     * nothing.
     *
     * @throws TransportException if a connection could not to be established.
     */
    public void open(Queue<DeviceClientConfig> deviceClientConfigs) throws TransportException
    {
        if (deviceClientConfigs.size() > 1)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_022: [If the list of device client configuration objects is larger than 1, this function shall throw an UnsupportedOperationException.]
            throw new UnsupportedOperationException("Mqtt does not support Multiplexing");
        }
        synchronized (MQTT_CONNECTION_LOCK)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_15_006: [If the MQTT connection is already open,
            // the function shall do nothing.]
            if (this.state == IotHubConnectionStatus.CONNECTED)
            {
                return;
            }

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_004: [The function shall establish an MQTT connection
            // with an IoT Hub using the provided host name, user name, device ID, and sas token.]
            try
            {
                SSLContext sslContext = null;
                if (this.config.getAuthenticationType() == DeviceClientConfig.AuthType.SAS_TOKEN)
                {
                    this.iotHubUserPassword = this.config.getSasTokenAuthentication().getRenewedSasToken();
                    sslContext = this.config.getSasTokenAuthentication().getSSLContext();
                }
                else if (this.config.getAuthenticationType() == DeviceClientConfig.AuthType.X509_CERTIFICATE)
                {
                    if (this.config.isUseWebsocket())
                    {
                        //Codes_SRS_MQTTIOTHUBCONNECTION_34_027: [If this function is called while using websockets and x509 authentication, an UnsupportedOperationException shall be thrown.]
                        throw new UnsupportedOperationException("X509 authentication is not supported over MQTT_WS");
                    }

                    this.iotHubUserPassword = null;
                    sslContext = this.config.getX509Authentication().getSSLContext();
                }

                //URLEncoder follows HTML spec for encoding urls, which includes substituting space characters with '+'
                // We want "%20" for spaces, not '+', however, so replace them manually
                String clientIdentifier = "DeviceClientType=" + URLEncoder.encode(TransportUtils.USER_AGENT_STRING, "UTF-8").replaceAll("\\+", "%20");
                this.iotHubUserName = this.config.getIotHubHostname() + "/" + this.config.getDeviceId() + "/" + TWIN_API_VERSION + "&" + clientIdentifier;

                if (this.config.isUseWebsocket())
                {
                    //Codes_SRS_MQTTIOTHUBCONNECTION_25_018: [The function shall establish an MQTT WS connection with a server uri as wss://<hostName>/$iothub/websocket?iothub-no-client-cert=true if websocket was enabled.]
                    final String wsServerUri = WS_SSL_PREFIX + this.config.getIotHubHostname() + WEBSOCKET_RAW_PATH + WEBSOCKET_QUERY ;
                    mqttConnection = new MqttConnection(wsServerUri,
                            this.config.getDeviceId(), this.iotHubUserName, this.iotHubUserPassword, sslContext);
                }
                else
                {
                    //Codes_SRS_MQTTIOTHUBCONNECTION_25_019: [The function shall establish an MQTT connection with a server uri as ssl://<hostName>:8883 if websocket was not enabled.]
                    final String serverUri = SSL_PREFIX + this.config.getIotHubHostname() + SSL_PORT_SUFFIX;
                    mqttConnection = new MqttConnection(serverUri,
                            this.config.getDeviceId(), this.iotHubUserName, this.iotHubUserPassword, sslContext);
                }

                //Codes_SRS_MQTTIOTHUBCONNECTION_34_030: [This function shall instantiate this object's MqttMessaging object with this object as the listener.]
                this.deviceMessaging = new MqttMessaging(mqttConnection, this.config.getDeviceId(), this.listener, this);
                this.mqttConnection.setMqttCallback(this.deviceMessaging);
                this.deviceMethod = new MqttDeviceMethod(mqttConnection);
                this.deviceTwin = new MqttDeviceTwin(mqttConnection);

                this.deviceMessaging.start();
                this.state = IotHubConnectionStatus.CONNECTED;
            }
            catch (IOException e)
            {
                this.state = IotHubConnectionStatus.DISCONNECTED;
                // Codes_SRS_MQTTIOTHUBCONNECTION_15_005: [If an MQTT connection is unable to be established
                // for any reason, the function shall throw a TransportException.]
                if (this.deviceMethod != null)
                {
                    this.deviceMethod.stop();
                }
                if (this.deviceTwin != null )
                {
                    this.deviceTwin.stop();
                }
                if (this.deviceMessaging != null)
                {
                    this.deviceMessaging.stop();
                }
                throw new TransportException(e);
            }
        }
    }

    /**
     * Closes the connection. After the connection is closed, it is no longer usable.
     * If the connection is already closed, the function shall do nothing.
     */
    public void close() throws TransportException
    {
        // Codes_SRS_MQTTIOTHUBCONNECTION_15_007: [If the MQTT session is closed, the function shall do nothing.]
        if (this.state == IotHubConnectionStatus.DISCONNECTED)
        {
            return;
        }

        // Codes_SRS_MQTTIOTHUBCONNECTION_15_006: [The function shall close the MQTT connection.]
        try
        {
            if (this.deviceMethod != null)
            {
                this.deviceMethod.stop();
                this.deviceMethod = null;
            }

            if (this.deviceTwin != null)
            {
                this.deviceTwin.stop();
                this.deviceTwin = null;
            }

            if (this.deviceMessaging != null)
            {
                this.deviceMessaging.stop();
                this.deviceMessaging = null;
            }

            this.state = IotHubConnectionStatus.DISCONNECTED;
        }
        catch (TransportException e)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_021: [If a TransportException is encountered while closing the three clients, this function shall set this object's state to closed and then rethrow the exception.]
            this.state = IotHubConnectionStatus.DISCONNECTED;
            throw e;
        }
    }

    /**
     * Sends an event message.
     *
     * @param message the event message.
     *
     * @return the status code from sending the event message.
     *
     * @throws IllegalStateException if the MqttIotHubConnection is not open
     */
    public IotHubStatusCode sendEvent(Message message) throws IllegalStateException
    {
        synchronized (MQTT_CONNECTION_LOCK)
        {
            // Codes_SRS_MQTTIOTHUBCONNECTION_15_010: [If the message is null or empty,
            // the function shall return status code BAD_FORMAT.]
            if (message == null || message.getBytes() == null ||
                    (
                            (message.getMessageType() != MessageType.DEVICE_TWIN
                                    && message.getMessageType() != MessageType.DEVICE_METHODS)
                                    && message.getBytes().length == 0))
            {
                return IotHubStatusCode.BAD_FORMAT;
            }

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_013: [If the MQTT connection is closed,
            // the function shall throw an IllegalStateException.]
            if (this.state == IotHubConnectionStatus.DISCONNECTED)
            {
                throw new IllegalStateException("Cannot send event using a closed MQTT connection");
            }

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_008: [The function shall send an event message
            // to the IoT Hub given in the configuration.]
            // Codes_SRS_MQTTIOTHUBCONNECTION_15_011: [If the message was successfully received by the service,
            // the function shall return status code OK_EMPTY.]
            IotHubStatusCode result = IotHubStatusCode.OK_EMPTY;

            try
            {
                // Codes_SRS_MQTTIOTHUBCONNECTION_15_009: [The function shall send the message payload.]
                if (message.getMessageType() == MessageType.DEVICE_METHODS)
                {
                    this.deviceMethod.start();
                    this.deviceMethod.send((IotHubTransportMessage) message);
                }
                else if (message.getMessageType() == MessageType.DEVICE_TWIN)
                {
                    this.deviceTwin.start();
                    this.deviceTwin.send((IotHubTransportMessage) message);
                }
                else
                {
                    this.deviceMessaging.send(message);
                }
            }
            // Codes_SRS_MQTTIOTHUBCONNECTION_15_012: [If the message was not successfully
            // received by the service, the function shall return status code ERROR.]
            catch (Exception e)
            {
                result = IotHubStatusCode.ERROR;
            }

            return result;
        }
    }

    /**
     * Receives a message, if one exists.
     *
     * @return the message received, or null if none exists.
     *
     * @throws TransportException if the connection state is currently closed.
     */
    private IotHubTransportMessage receiveMessage() throws TransportException
    {
        // Codes_SRS_MQTTIOTHUBCONNECTION_15_014: [The function shall attempt to consume a message
        // from various messaging clients.]
        System.out.println("Trying to receive over methods...");
        IotHubTransportMessage message = this.deviceMethod.receive();
        if (message == null)
        {
            System.out.println("Trying to receive over twin...");
            message = deviceTwin.receive();
        }

        if (message == null)
        {
            System.out.println("Trying to receive over telemetry...");
            message = deviceMessaging.receive();
        }

        return message;
    }

    @Override
    public void setListener(IotHubListener listener) throws IllegalArgumentException
    {
        if (listener == null)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_049: [If the provided listener object is null, this function shall throw an IllegalArgumentException.]
            throw new IllegalArgumentException("listener cannot be null");
        }

        //Codes_SRS_MQTTIOTHUBCONNECTION_34_050: [This function shall save the provided listener object.]
        this.listener = listener;
    }

    /**
     * Sends an event message.
     *
     * @param message the event message.
     *
     * @return the status code from sending the event message.
     *
     * @throws TransportException if the MqttIotHubConnection is not open
     */
    @Override
    public IotHubStatusCode sendMessage(Message message) throws TransportException
    {
        synchronized (MQTT_CONNECTION_LOCK)
        {
            // Codes_SRS_MQTTIOTHUBCONNECTION_15_010: [If the message is null or empty,
            // the function shall return status code BAD_FORMAT.]
            if (message == null || message.getBytes() == null ||
                    (
                            (message.getMessageType() != DEVICE_TWIN
                                    && message.getMessageType() != DEVICE_METHODS)
                                    && message.getBytes().length == 0))
            {
                return IotHubStatusCode.BAD_FORMAT;
            }

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_013: [If the MQTT connection is closed, the function shall throw an IllegalStateException.]
            if (this.state == IotHubConnectionStatus.DISCONNECTED)
            {
                throw new IllegalStateException("Cannot send event using a closed MQTT connection");
            }

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_008: [The function shall send an event message
            // to the IoT Hub given in the configuration.]
            // Codes_SRS_MQTTIOTHUBCONNECTION_15_011: [If the message was successfully received by the service,
            // the function shall return status code OK_EMPTY.]
            IotHubStatusCode result = IotHubStatusCode.OK_EMPTY;

            // Codes_SRS_MQTTIOTHUBCONNECTION_15_009: [The function shall send the message payload.]
            if (message.getMessageType() == DEVICE_METHODS)
            {
                this.deviceMethod.start();
                this.deviceMethod.send((IotHubTransportMessage) message);
            }
            else if (message.getMessageType() == DEVICE_TWIN)
            {
                this.deviceTwin.start();
                this.deviceTwin.send((IotHubTransportMessage) message);
            }
            else
            {
                this.deviceMessaging.send(message);
            }

            return result;
        }
    }

    /**
     * Sends an ACK to the service for the provided message
     * @param message the message to acknowledge to the service
     * @param result Ignored. The only ack that can be sent in MQTT is COMPLETE
     * @return true if the ACK was sent successfully and false otherwise
     * @throws TransportException if the ACK could not be sent successfully
     */
    @Override
    public boolean sendMessageResult(Message message, IotHubMessageResult result) throws TransportException
    {
        if (message == null || result == null)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_057: [If the provided message or result is null, this function shall throw a TransportException.]
            throw new TransportException(new IllegalArgumentException("message and result must be non-null"));
        }

        int messageId;
        if (receivedMessagesToAcknowledge.containsKey(message))
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_052: [If this object has received the provided message from the service, this function shall retrieve the Mqtt messageId for that message.]
            messageId = receivedMessagesToAcknowledge.get(message);
        }
        else
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_051: [If this object has not received the provided message from the service, this function shall throw a TransportException.]
            throw new TransportException(new IllegalArgumentException("Provided message cannot be acknowledged because it was already acknowledged or was never received from service"));
        }

        boolean ackSent;
        if (message.getMessageType() == DEVICE_METHODS)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_053: [If the provided message has message type DEVICE_METHODS, this function shall invoke the methods client to send the ack and return the result.]
            this.deviceMethod.start();
            ackSent = this.deviceMethod.sendMessageAcknowledgement(messageId);
        }
        else if (message.getMessageType() == DEVICE_TWIN)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_054: [If the provided message has message type DEVICE_TWIN, this function shall invoke the twin client to send the ack and return the result.]
            this.deviceTwin.start();
            ackSent = this.deviceTwin.sendMessageAcknowledgement(messageId);
        }
        else
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_055: [If the provided message has message type other than DEVICE_METHODS and DEVICE_TWIN, this function shall invoke the telemetry client to send the ack and return the result.]
            ackSent = this.deviceMessaging.sendMessageAcknowledgement(messageId);
        }

        if (ackSent)
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_056: [If the ack was sent successfully, this function shall remove the provided message from the saved map of messages to acknowledge.]
            this.receivedMessagesToAcknowledge.remove(message);
        }

        System.out.println("ack sent status: " + ackSent);

        return ackSent;
    }

    @Override
    public void onMessageArrived(int messageId)
    {
        IotHubTransportMessage transportMessage = null;
        try
        {
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_058: [This function shall attempt to receive a message.]
            transportMessage = this.receiveMessage();
            System.out.println("received message, but was it null?");
        }
        catch (TransportException e)
        {
            System.out.println("Couldn't receive message :( " + e);
            this.listener.onMessageReceived(null, new TransportException("Failed to receive message from service", e));
            this.logger.LogInfo("Encountered exception while receiving message from Iot Hub over MQTT");
            this.logger.LogError(e);
        }

        if (transportMessage == null)
        {
            System.out.println("Null message arrived, just ignoring it");
            //Ack is not sent to service for this message because we cannot interpret the message. Service will likely re-send
            this.listener.onMessageReceived(null, new TransportException("Message sent from service could not be parsed"));
            this.logger.LogInfo("Message arrived from IoT Hub that could not be parsed. That message has been ignored.");
        }
        else
        {
            System.out.println("so far so good!");
            //Codes_SRS_MQTTIOTHUBCONNECTION_34_059: [If a transport message is successfully received, this function shall save it in this object's map of messages to be acknowledged along with the provided messageId.]
            this.receivedMessagesToAcknowledge.put(transportMessage, messageId);

            System.out.println("determining message type of arrived message...");

            switch (transportMessage.getMessageType())
            {
                case DEVICE_TWIN:
                    //Codes_SRS_MQTTIOTHUBCONNECTION_34_060: [If a transport message is successfully received, and the message has a type of DEVICE_TWIN, this function shall set the callback and callback context of this object from the saved values in config for methods.]
                    System.out.println("TWIN MESSAGE ARRIVED");
                    transportMessage.setMessageCallback(this.config.getDeviceTwinMessageCallback());
                    transportMessage.setMessageCallbackContext(this.config.getDeviceTwinMessageContext());
                    break;
                case DEVICE_METHODS:
                    //Codes_SRS_MQTTIOTHUBCONNECTION_34_061: [If a transport message is successfully received, and the message has a type of DEVICE_METHODS, this function shall set the callback and callback context of this object from the saved values in config for twin.]
                    System.out.println("METHODS MESSAGE ARRIVED");
                    transportMessage.setMessageCallback(this.config.getDeviceMethodsMessageCallback());
                    transportMessage.setMessageCallbackContext(this.config.getDeviceMethodsMessageContext());
                    break;
                case DEVICE_TELEMETRY:
                    System.out.println("TELEMETRY MESSAGE ARRIVED");
                    //Codes_SRS_MQTTIOTHUBCONNECTION_34_062: [If a transport message is successfully received, and the message has a type of DEVICE_TELEMETRY, this function shall set the callback and callback context of this object from the saved values in config for telemetry.]
                    transportMessage.setMessageCallback(this.config.getDeviceTelemetryMessageCallback());
                    transportMessage.setMessageCallbackContext(this.config.getDeviceTelemetryMessageContext());
                    break;
                case UNKNOWN:
                case CBS_AUTHENTICATION:
                default:
                    //do nothing
                    System.out.println("NOT SURE WHAT MESSAgE TYPE ARRIVEDE?????");

            }

            //Codes_SRS_MQTTIOTHUBCONNECTION_34_063: [If a transport message is successfully received, this function shall notify its listener that a message was received and provide the received message.]
            this.listener.onMessageReceived(transportMessage, null);
        }
    }
}
