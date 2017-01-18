/*
 * Copyright Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hextremelabs.gcm.client;

import com.hextremelabs.quickee.configuration.Config;
import com.hextremelabs.quickee.configuration.Key;
import com.hextremelabs.quickee.core.DataHelper;
import com.hextremelabs.quickee.response.BaseResponse;
import com.hextremelabs.quickee.response.DefaultResponses;
import com.hextremelabs.quickee.response.ResponseCodes;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Schedule;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.hextremelabs.gcm.client.Constants.GCM_SEND_ENDPOINT;
import static com.hextremelabs.gcm.client.Constants.JSON_CANONICAL_IDS;
import static com.hextremelabs.gcm.client.Constants.JSON_ERROR;
import static com.hextremelabs.gcm.client.Constants.JSON_FAILURE;
import static com.hextremelabs.gcm.client.Constants.JSON_MESSAGE_ID;
import static com.hextremelabs.gcm.client.Constants.JSON_MULTICAST_ID;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_BADGE;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_BODY;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_BODY_LOC_ARGS;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_BODY_LOC_KEY;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_CLICK_ACTION;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_COLOR;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_ICON;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_SOUND;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_TAG;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_TITLE;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_TITLE_LOC_ARGS;
import static com.hextremelabs.gcm.client.Constants.JSON_NOTIFICATION_TITLE_LOC_KEY;
import static com.hextremelabs.gcm.client.Constants.JSON_PAYLOAD;
import static com.hextremelabs.gcm.client.Constants.JSON_REGISTRATION_IDS;
import static com.hextremelabs.gcm.client.Constants.JSON_RESULTS;
import static com.hextremelabs.gcm.client.Constants.JSON_SUCCESS;
import static com.hextremelabs.gcm.client.Constants.JSON_TO;
import static com.hextremelabs.gcm.client.Constants.PARAM_COLLAPSE_KEY;
import static com.hextremelabs.gcm.client.Constants.PARAM_CONTENT_AVAILABLE;
import static com.hextremelabs.gcm.client.Constants.PARAM_DELAY_WHILE_IDLE;
import static com.hextremelabs.gcm.client.Constants.PARAM_DRY_RUN;
import static com.hextremelabs.gcm.client.Constants.PARAM_PRIORITY;
import static com.hextremelabs.gcm.client.Constants.PARAM_RESTRICTED_PACKAGE_NAME;
import static com.hextremelabs.gcm.client.Constants.PARAM_TIME_TO_LIVE;
import static com.hextremelabs.gcm.client.Constants.TOKEN_CANONICAL_REG_ID;
import static com.hextremelabs.gcm.client.Constants.TOPIC_PREFIX;
import static com.hextremelabs.quickee.response.ResponseCodes.REQUEST_SUCCESSFUL;
import static com.hextremelabs.quickee.response.ResponseCodes.TRANSACTION_FAILED;

/**
 * Helper class to send messages to the GCM service using an API Key.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class Sender {

  protected static final String UTF8 = "UTF-8";

  /**
   * Initial delay before first retry, without jitter.
   */
  protected static final int BACKOFF_INITIAL_DELAY = 1000;

  /**
   * Maximum delay before a retry.
   */
  protected static final int MAX_BACKOFF_DELAY = 1024000;

  protected static final Logger L = LoggerFactory.getLogger(Sender.class);

  private static final ConcurrentLinkedQueue<GcmResult> MESSAGE_QUEUE = new ConcurrentLinkedQueue<>();

  protected final Random random = new Random();

  @Inject
  @Config
  @Key("gcm.api.key")
  private String key;

  @Inject
  @Config
  @Key("gcm.ttl")
  private Integer timeToLive;

  @Inject
  @Config
  @Key("gcm.messages.persistent")
  private Boolean persistent;

  @Inject
  private DefaultResponses dr;

  @PersistenceContext
  private EntityManager em;

  private static void close(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        L.debug("IOException closing stream", e);
      }
    }
  }

  /**
   * Creates a map with just one key-value pair.
   */
  protected static Map<String, String> newKeyValues(String key, String value) {
    Map<String, String> keyValues = new HashMap<>(1);
    keyValues.put(nonNull(key), nonNull(value));
    return keyValues;
  }

  /**
   * Creates a {@link StringBuilder} to be used as the body of an HTTP POST.
   *
   * @param name initial parameter for the POST.
   * @param value initial value for that parameter.
   * @return StringBuilder to be used an HTTP POST body.
   */
  protected static StringBuilder newBody(String name, String value) {
    return new StringBuilder(nonNull(name)).append('=').append(nonNull(value));
  }

  /**
   * Adds a new parameter to the HTTP POST body.
   *
   * @param body HTTP POST body.
   * @param name parameter's name.
   * @param value parameter's value.
   */
  protected static void addParameter(StringBuilder body, String name, String value) {
    nonNull(body).append('&')
        .append(nonNull(name)).append('=').append(nonNull(value));
  }

  /**
   * Convenience method to convert an InputStream to a String.
   * <p>
   * If the stream ends in a newline character, it will be stripped.
   * <p>
   * If the stream is {@literal null}, returns an empty string.
   */
  protected static String getString(InputStream stream) throws IOException {
    if (stream == null) {
      return "";
    }

    BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
    StringBuilder content = new StringBuilder();

    String newLine;
    do {
      newLine = reader.readLine();

      if (newLine != null) {
        content.append(newLine).append('\n');
      }
    } while (newLine != null);

    if (content.length() > 0) {
      // strip last newline
      content.setLength(content.length() - 1);
    }

    return content.toString();
  }

  private static String getAndClose(InputStream stream) throws IOException {
    try {
      return getString(stream);
    } finally {
      if (stream != null) {
        close(stream);
      }
    }
  }

  static <T> T nonNull(T argument) {
    if (argument == null) {
      throw new IllegalArgumentException("argument cannot be null");
    }

    return argument;
  }

  public BaseResponse<GcmResult> send(String message, String to) {
    Message msg = toGcmMessage(message);
    BaseResponse<GcmResult> response;
    try {
      GcmResult result = send(msg, to, 3);
      if (result.getMessageId() == null) {
        response = new BaseResponse<>(TRANSACTION_FAILED, result.getErrorCodeName(), result);
      } else {
        L.info("Push notification sent to {}. Result = {}", to, result);
        if (persistent) {
          MESSAGE_QUEUE.add(result);
        }
        response = new BaseResponse<>(result);
      }
    } catch (IOException ex) {
      L.warn("Error contacting GCM.", ex);
      response = new BaseResponse<>(ResponseCodes.SERVICE_ERROR, "Error contacting GCM.");
    }

    return response;
  }

  public BaseResponse<MulticastResult> send(String message, Collection<String> to) {
    if (DataHelper.hasBlank(to)) {
      return new BaseResponse<>();
    }

    Message msg = toGcmMessage(message);
    BaseResponse<MulticastResult> response;
    try {
      MulticastResult mr = send(msg, to, 3);
      L.info("Multicast message sent. {} succeeded, {} failed.", mr.getSuccess(), mr.getFailure());
      if (persistent) {
        mr.getResults().forEach(MESSAGE_QUEUE::add);
      }
      response = new BaseResponse<>(dr.status(mr.getFailure() == 0
          ? REQUEST_SUCCESSFUL : mr.getSuccess() == 0
          ? TRANSACTION_FAILED : ResponseCodes.PARTIAL_SUCCESS), mr);
    } catch (IOException ex) {
      L.warn("Error contacting GCM.", ex);
      response = new BaseResponse<>(ResponseCodes.SERVICE_ERROR, "Error contacting GCM.");
    }

    return response;
  }

  public BaseResponse<GcmResult> broadcast(String message, String topic) {
    return send(message, "/topics/" + topic);
  }

  private Message toGcmMessage(String message) {
    Message msg = new Message.Builder()
        //.collapseKey(messageJson)
        .timeToLive(timeToLive)
        //.delayWhileIdle(true)
        .addData("message", message)
        //.notification(new Notification.Builder("clear_grey600").title(message.messageType()).body(messageJson).build())
        .build();
    return msg;
  }

  /**
   * Sends a message to one device, retrying in case of unavailability.
   *
   * <p>
   * <strong>Note: </strong> this method uses exponential back-off to retry in case of service unavailability and hence
   * could block the calling thread for many seconds.
   *
   * @param message message to be sent, including the device's registration id.
   * @param to registration token, notification key, or topic where the message will be sent.
   * @param retries number of retries in case of service unavailability errors.
   *
   * @return result of the request (see its javadoc for more details).
   *
   * @throws IllegalArgumentException if to is {@literal null}.
   * @throws InvalidRequestException if GCM didn't returned a 200 or 5xx status.
   * @throws IOException if message could not be sent.
   */
  public GcmResult send(Message message, String to, int retries) throws IOException {
    int attempt = 0;
    GcmResult result;
    int backoff = BACKOFF_INITIAL_DELAY;
    boolean tryAgain;
    do {
      attempt++;
      L.debug("Attempt #{} to send message {} to regIds {}", attempt, message, to);

      result = sendNoRetry(message, to);
      tryAgain = result == null && attempt <= retries;

      if (tryAgain) {
        int sleepTime = backoff / 2 + random.nextInt(backoff);
        sleep(sleepTime);

        if (2 * backoff < MAX_BACKOFF_DELAY) {
          backoff *= 2;
        }
      }
    } while (tryAgain);

    if (result == null) {
      throw new IOException("Could not send message after " + attempt + " attempts");
    }

    return result;
  }

  /**
   * Sends a message without retrying in case of service unavailability. See {@link #send(Message, String, int)} for
   * more info.
   *
   * @return result of the post, or {@literal null} if the GCM service was unavailable or any network exception caused
   * the request to fail, or if the response contains more than one result.
   *
   * @throws InvalidRequestException if GCM didn't returned a 200 status.
   * @throws IllegalArgumentException if to is {@literal null}.
   */
  public GcmResult sendNoRetry(Message message, String to) throws IOException {
    nonNull(to);
    Map<Object, Object> jsonRequest = new HashMap<>();
    messageToMap(message, jsonRequest);
    jsonRequest.put(JSON_TO, to);
    String responseBody = makeGcmHttpRequest(jsonRequest);
    L.info("RESPONSE BODY: {}", responseBody);

    if (responseBody == null) {
      return null;
    }

    JSONParser parser = new JSONParser();
    JSONObject jsonResponse;
    try {
      jsonResponse = (JSONObject) parser.parse(responseBody);
      GcmResult.Builder resultBuilder = new GcmResult.Builder();
      if (jsonResponse.containsKey("results")) {
        // Handle response from message sent to specific device.
        JSONArray jsonResults = (JSONArray) jsonResponse.get("results");
        if (jsonResults.size() == 1) {
          JSONObject jsonResult = (JSONObject) jsonResults.get(0);
          String messageId = (String) jsonResult.get(JSON_MESSAGE_ID);
          String canonicalRegId = (String) jsonResult.get(TOKEN_CANONICAL_REG_ID);
          String error = (String) jsonResult.get(JSON_ERROR);
          resultBuilder.messageId(messageId)
              .canonicalRegistrationId(canonicalRegId)
              .errorCode(error);
        } else {
          L.warn("Found null or {} results, expected one", jsonResults.size());
          return null;
        }
      } else if (to.startsWith(TOPIC_PREFIX)) {
        if (jsonResponse.containsKey(JSON_MESSAGE_ID)) {
          // message_id is expected when this is the response from a topic message.
          Long messageId = (Long) jsonResponse.get(JSON_MESSAGE_ID);
          resultBuilder.messageId(messageId.toString());
        } else if (jsonResponse.containsKey(JSON_ERROR)) {
          String error = (String) jsonResponse.get(JSON_ERROR);
          resultBuilder.errorCode(error);
        } else {
          L.warn("Expected {} or {} found: {}", JSON_MESSAGE_ID, JSON_ERROR, responseBody);
          return null;
        }
      } else if (jsonResponse.containsKey(JSON_SUCCESS) && jsonResponse.containsKey(JSON_FAILURE)) {
        // success and failure are expected when response is from group message.
        int success = getNumber(jsonResponse, JSON_SUCCESS).intValue();
        int failure = getNumber(jsonResponse, JSON_FAILURE).intValue();
        List<String> failedIds = null;
        if (jsonResponse.containsKey("failed_registration_ids")) {
          JSONArray jFailedIds = (JSONArray) jsonResponse.get("failed_registration_ids");
          failedIds = new ArrayList<>();
          for (int i = 0; i < jFailedIds.size(); i++) {
            failedIds.add((String) jFailedIds.get(i));
          }
        }
        resultBuilder.success(success).failure(failure)
            .failedRegistrationIds(failedIds);
      } else {
        L.warn("Unrecognized response: {}", responseBody);
        throw newIoException(responseBody, new Exception("Unrecognized response."));
      }

      return resultBuilder.build();
    } catch (ParseException e) {
      throw newIoException(responseBody, e);
    }
  }

  /**
   * Sends a message to many devices, retrying in case of unavailability.
   *
   * <p>
   * <strong>Note: </strong> this method uses exponential back-off to retry in case of service unavailability and hence
   * could block the calling thread for many seconds.
   *
   * @param message message to be sent.
   * @param regIds registration id of the devices that will receive the message.
   * @param retries number of retries in case of service unavailability errors.
   *
   * @return combined result of all requests made.
   *
   * @throws IllegalArgumentException if registrationIds is {@literal null} or empty.
   * @throws InvalidRequestException if GCM didn't returned a 200 or 503 status.
   * @throws IOException if message could not be sent.
   */
  public MulticastResult send(Message message, Collection<String> regIds, int retries) throws IOException {
    int attempt = 0;
    MulticastResult multicastResult;
    int backoff = BACKOFF_INITIAL_DELAY;
    // Map of results by registration id, it will be updated after each attempt
    // to send the messages
    Map<String, GcmResult> results = new HashMap<>();
    List<String> unsentRegIds = new ArrayList<>(regIds);
    boolean tryAgain;
    List<Long> multicastIds = new ArrayList<>();
    do {
      multicastResult = null;
      attempt++;
      L.debug("Attempt #{} to send message {} to regIds {}", attempt, message, unsentRegIds);
      try {
        multicastResult = sendNoRetry(message, unsentRegIds);
      } catch (IOException e) {
        // no need for WARNING since exception might be already logged
        L.debug("IOException on attempt {}", attempt, e);
      }
      if (multicastResult != null) {
        long multicastId = multicastResult.getMulticastId();
        L.debug("multicast_id on attempt # {}: ", attempt, multicastId);
        multicastIds.add(multicastId);
        unsentRegIds = updateStatus(unsentRegIds, results, multicastResult);
        tryAgain = !unsentRegIds.isEmpty() && attempt <= retries;
      } else {
        tryAgain = attempt <= retries;
      }
      if (tryAgain) {
        int sleepTime = backoff / 2 + random.nextInt(backoff);
        sleep(sleepTime);
        if (2 * backoff < MAX_BACKOFF_DELAY) {
          backoff *= 2;
        }
      }
    } while (tryAgain);
    if (multicastIds.isEmpty()) {
      // all JSON posts failed due to GCM unavailability
      throw new IOException("Could not post JSON requests to GCM after " + attempt + " attempts");
    }
    // calculate summary
    int success = 0, failure = 0, canonicalIds = 0;
    for (GcmResult result : results.values()) {
      if (result.getMessageId() != null) {
        success++;
        if (result.getCanonicalRegistrationId() != null) {
          canonicalIds++;
        }
      } else {
        failure++;
      }
    }
    // build a new object with the overall result
    long multicastId = multicastIds.remove(0);
    MulticastResult.Builder builder = new MulticastResult.Builder(success, failure, canonicalIds, multicastId)
        .retryMulticastIds(multicastIds);
    // add results, in the same order as the input
    regIds.stream().map((regId) -> results.get(regId)).forEach((result) -> {
      builder.addResult(result);
    });

    return builder.build();
  }

  /**
   * Updates the status of the messages sent to devices and the list of devices that should be retried.
   *
   * @param unsentRegIds list of devices that are still pending an update.
   * @param allResults map of status that will be updated.
   * @param multicastResult result of the last multicast sent.
   *
   * @return updated version of devices that should be retried.
   */
  private List<String> updateStatus(List<String> unsentRegIds, Map<String, GcmResult> allResults,
      MulticastResult multicastResult) {
    List<GcmResult> results = multicastResult.getResults();
    if (results.size() != unsentRegIds.size()) {
      // should never happen, unless there is a flaw in the algorithm
      throw new RuntimeException("Internal error: sizes do not match. " + "currentResults: " + results
          + "; unsentRegIds: " + unsentRegIds);
    }

    List<String> newUnsentRegIds = new ArrayList<>();
    for (int i = 0; i < unsentRegIds.size(); i++) {
      String regId = unsentRegIds.get(i);
      GcmResult result = results.get(i);
      allResults.put(regId, result);
      String error = result.getErrorCodeName();
      if (error != null && (error.equals(Constants.ERROR_UNAVAILABLE)
          || error.equals(Constants.ERROR_INTERNAL_SERVER_ERROR))) {
        newUnsentRegIds.add(regId);
      }
    }

    return newUnsentRegIds;
  }

  /**
   * Sends a message without retrying in case of service unavailability. See {@link #send(String, Collection)}} for more
   * info.
   *
   * @return multicast results if the message was sent successfully, {@literal null} if it failed but could be retried.
   *
   * @throws IllegalArgumentException if registrationIds is {@literal null} or empty.
   * @throws InvalidRequestException if GCM didn't returned a 200 status.
   * @throws IOException if there was a JSON parsing error
   */
  public MulticastResult sendNoRetry(Message message, Collection<String> registrationIds) throws IOException {
    if (nonNull(registrationIds).isEmpty()) {
      throw new IllegalArgumentException("registrationIds cannot be empty");
    }

    Map<Object, Object> jsonRequest = new HashMap<>();
    messageToMap(message, jsonRequest);
    jsonRequest.put(JSON_REGISTRATION_IDS, registrationIds);
    String responseBody = makeGcmHttpRequest(jsonRequest);

    if (responseBody == null) {
      return null;
    }

    JSONParser parser = new JSONParser();
    JSONObject jsonResponse;
    try {
      jsonResponse = (JSONObject) parser.parse(responseBody);
      int success = getNumber(jsonResponse, JSON_SUCCESS).intValue();
      int failure = getNumber(jsonResponse, JSON_FAILURE).intValue();
      int canonicalIds = getNumber(jsonResponse, JSON_CANONICAL_IDS).intValue();
      long multicastId = getNumber(jsonResponse, JSON_MULTICAST_ID).longValue();
      MulticastResult.Builder builder = new MulticastResult.Builder(success, failure, canonicalIds, multicastId);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) jsonResponse.get(JSON_RESULTS);
      if (results != null) {
        for (Map<String, Object> jsonResult : results) {
          String messageId = (String) jsonResult.get(JSON_MESSAGE_ID);
          String canonicalRegId
              = (String) jsonResult.get(TOKEN_CANONICAL_REG_ID);
          String error = (String) jsonResult.get(JSON_ERROR);
          GcmResult result = new GcmResult.Builder()
              .messageId(messageId)
              .canonicalRegistrationId(canonicalRegId)
              .errorCode(error)
              .build();
          builder.addResult(result);
        }
      }
      return builder.build();
    } catch (ParseException e) {
      throw newIoException(responseBody, e);
    }
  }

  private String makeGcmHttpRequest(Map<Object, Object> jsonRequest) throws InvalidRequestException {
    String requestBody = JSONValue.toJSONString(jsonRequest);
    L.debug("JSON request: {}", requestBody);

    HttpURLConnection conn;
    int status;
    try {
      conn = post(GCM_SEND_ENDPOINT, "application/json", requestBody);
      status = conn.getResponseCode();
    } catch (IOException e) {
      L.debug("IOException posting to GCM", e);
      return null;
    }

    String responseBody;
    if (status != 200) {
      try {
        responseBody = getAndClose(conn.getErrorStream());
        L.debug("JSON error response: {}", responseBody);
      } catch (IOException e) {
        // ignore the exception since it will thrown an InvalidRequestException anyways
        responseBody = "N/A";
        L.debug("Exception reading response: {}", e);
      }
      throw new InvalidRequestException(status, responseBody);
    }

    try {
      responseBody = getAndClose(conn.getInputStream());
    } catch (IOException e) {
      L.warn("IOException reading response", e);
      return null;
    }

    L.debug("JSON response: {}", responseBody);
    return responseBody;
  }

  /**
   * Populate Map with message.
   *
   * @param message Message used to populate Map.
   * @param mapRequest Map populated by Message.
   */
  private void messageToMap(Message message, Map<Object, Object> mapRequest) {
    if (message == null || mapRequest == null) {
      return;
    }

    setJsonField(mapRequest, PARAM_PRIORITY, message.getPriority());
    setJsonField(mapRequest, PARAM_CONTENT_AVAILABLE, message.getContentAvailable());
    setJsonField(mapRequest, PARAM_TIME_TO_LIVE, message.getTimeToLive());
    setJsonField(mapRequest, PARAM_COLLAPSE_KEY, message.getCollapseKey());
    setJsonField(mapRequest, PARAM_RESTRICTED_PACKAGE_NAME, message.getRestrictedPackageName());
    setJsonField(mapRequest, PARAM_DELAY_WHILE_IDLE, message.isDelayWhileIdle());
    setJsonField(mapRequest, PARAM_DRY_RUN, message.isDryRun());

    Map<String, String> payload = message.getData();
    if (!payload.isEmpty()) {
      mapRequest.put(JSON_PAYLOAD, payload);
    }

    if (message.getNotification() != null) {
      Notification notification = message.getNotification();
      Map<Object, Object> nMap = new HashMap<>();

      if (notification.getBadge() != null) {
        setJsonField(nMap, JSON_NOTIFICATION_BADGE, notification.getBadge().toString());
      }

      setJsonField(nMap, JSON_NOTIFICATION_BODY, notification.getBody());
      setJsonField(nMap, JSON_NOTIFICATION_BODY_LOC_ARGS, notification.getBodyLocArgs());
      setJsonField(nMap, JSON_NOTIFICATION_BODY_LOC_KEY, notification.getBodyLocKey());
      setJsonField(nMap, JSON_NOTIFICATION_CLICK_ACTION, notification.getClickAction());
      setJsonField(nMap, JSON_NOTIFICATION_COLOR, notification.getColor());
      setJsonField(nMap, JSON_NOTIFICATION_ICON, notification.getIcon());
      setJsonField(nMap, JSON_NOTIFICATION_SOUND, notification.getSound());
      setJsonField(nMap, JSON_NOTIFICATION_TAG, notification.getTag());
      setJsonField(nMap, JSON_NOTIFICATION_TITLE, notification.getTitle());
      setJsonField(nMap, JSON_NOTIFICATION_TITLE_LOC_ARGS, notification.getTitleLocArgs());
      setJsonField(nMap, JSON_NOTIFICATION_TITLE_LOC_KEY, notification.getTitleLocKey());
      mapRequest.put(JSON_NOTIFICATION, nMap);
    }
  }

  private IOException newIoException(String responseBody, Exception e) {
    // log exception, as IOException constructor that takes a message and cause
    // is only available on Java 6
    String message = "Error parsing JSON response (" + responseBody + ")";
    L.warn(message, e);
    return new IOException(message + ":" + e);
  }

  /**
   * Sets a JSON field, but only if the value is not {@literal null}.
   */
  private void setJsonField(Map<Object, Object> json, String field, Object value) {
    if (value != null) {
      json.put(field, value);
    }
  }

  private Number getNumber(Map<?, ?> json, String field) {
    Object value = json.get(field);

    if (value == null) {
      throw new CustomParserException("Missing field: " + field);
    }

    if (!(value instanceof Number)) {
      throw new CustomParserException("Field " + field + " does not contain a number: " + value);
    }

    return (Number) value;
  }

  /**
   * Make an HTTP post to a given URL.
   *
   * @return HTTP response.
   */
  protected HttpURLConnection post(String url, String body) throws IOException {
    return post(url, "application/x-www-form-urlencoded;charset=UTF-8", body);
  }

  /**
   * Makes an HTTP POST request to a given endpoint.
   *
   * <p>
   * <strong>Note: </strong> the returned connected should not be disconnected, otherwise it would kill persistent
   * connections made using Keep-Alive.
   *
   * @param url endpoint to post the request.
   * @param contentType type of request.
   * @param body body of the request.
   *
   * @return the underlying connection.
   *
   * @throws IOException propagated from underlying methods.
   */
  protected HttpURLConnection post(String url, String contentType, String body) throws IOException {
    if (url == null || contentType == null || body == null) {
      throw new IllegalArgumentException("arguments cannot be null");
    }
    if (!url.startsWith("https://")) {
      L.warn("URL does not use https: {}", url);
    }

    L.info("Sending POST to {}", url);
    L.debug("POST body: {}", body);

    byte[] bytes = body.getBytes(UTF8);
    HttpURLConnection conn = getConnection(url);
    conn.setDoOutput(true);
    conn.setUseCaches(false);
    conn.setFixedLengthStreamingMode(bytes.length);
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", contentType);
    conn.setRequestProperty("Authorization", "key=" + key);
    OutputStream out = conn.getOutputStream();

    try {
      out.write(bytes);
    } finally {
      close(out);
    }

    return conn;
  }

  /**
   * Gets an {@link HttpURLConnection} given an URL.
   */
  protected HttpURLConnection getConnection(String url) throws IOException {
    return (HttpURLConnection) new URL(url).openConnection();
  }

  void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Schedule(hour = "*", minute = "*/5", persistent = false)
  @TransactionAttribute(TransactionAttributeType.REQUIRED)
  public void processQueue() {
    if (persistent) {
      int count = 0;
      while (!MESSAGE_QUEUE.isEmpty()) {
        em.persist(MESSAGE_QUEUE.poll());
        if (++count % 50 == 0) {
          em.flush();
        }
      }
    }
  }

  class CustomParserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CustomParserException(String message) {
      super(message);
    }
  }
}
