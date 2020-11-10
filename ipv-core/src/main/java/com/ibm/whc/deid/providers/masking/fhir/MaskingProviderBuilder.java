/*
 * (C) Copyright IBM Corp. 2016,2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.whc.deid.providers.masking.fhir;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ibm.whc.deid.providers.masking.AbstractComplexMaskingProvider;
import com.ibm.whc.deid.providers.masking.MaskingProvider;
import com.ibm.whc.deid.providers.masking.MaskingProviderFactory;
import com.ibm.whc.deid.shared.pojo.config.DeidMaskingConfig;
import com.ibm.whc.deid.shared.pojo.config.Rule;
import com.ibm.whc.deid.utils.log.LogCodes;
import scala.Tuple3;

/**
 * Generic Masking Provider
 *
 */
public class MaskingProviderBuilder extends AbstractComplexMaskingProvider<JsonNode> {

  private static final long serialVersionUID = 21789736594606147L;

  private final List<FHIRResourceMaskingAction> maskingActionList;
  private final boolean arrayAllRules;
  private boolean defNoRuleRes = true;

  private List<String> maskingAuditTrailList;

  private final String schemaType;

  public MaskingProviderBuilder(String schemaType,
      FHIRResourceMaskingConfiguration resourceConfiguration,
      DeidMaskingConfig maskingConfiguration, boolean arrayAllRules,
      boolean defNoRuleRes, MaskingProviderFactory maskingProviderFactory, String tenantId) {
    super(maskingConfiguration);

    this.schemaType = schemaType;
    this.maskingProviderFactory = maskingProviderFactory;
    this.arrayAllRules = arrayAllRules;
    this.defNoRuleRes = defNoRuleRes;

    // TODO: verify setting keyForType to empty string is reasonable
    this.keyForType = "";

    this.maskingActionList =
        buildMaskingActions(resourceConfiguration, maskingConfiguration, tenantId);
  }

  /**
   * Given the masking configuration, retrieves and sets the masking providers
   *
   * @param resourceConfiguration
   * @param deidMaskingConfig
   * @param tenantId
   * @return
   */
  private List<FHIRResourceMaskingAction> buildMaskingActions(
      FHIRResourceMaskingConfiguration resourceConfiguration, DeidMaskingConfig deidMaskingConfig,
      String tenantId) {

    List<FHIRResourceMaskingAction> maskingActions = new ArrayList<>();

    List<FHIRResourceField> fields = resourceConfiguration.getFields();
    String basePath = resourceConfiguration.getBasePath();
    String prefix = basePath.substring(0, basePath.lastIndexOf("/"));

    for (FHIRResourceField field : fields) {
      String ruleName = field.getShortRuleName();
      String fullRuleName = prefix + ruleName;
      String pathToIdentifier = field.getKey().replaceAll(basePath, "");

      checkIfValidPath(pathToIdentifier);

      Rule rule = deidMaskingConfig.getRulesMap().get(ruleName);
      if (rule == null) {
        // safety check - the configuration should already have been validated
        throw new RuntimeException("invalid masking configuration: no rule for " + ruleName);
      }

      rule.getMaskingProviders().stream().forEach(p -> {
        MaskingProvider maskingProvider =
            maskingProviderFactory.getProviderFromType(p.getType(), deidMaskingConfig, p, tenantId);
        maskingProvider.setName(ruleName);
        maskingActions.add(new FHIRResourceMaskingAction(fullRuleName, pathToIdentifier,
            maskingProvider, null, false));
      });
    }

    return maskingActions;
  }

  private void checkIfValidPath(String path) {
    // If provided more than 2 masking providers in the list
    if (path.contains("[") && path.contains("==")) {
      generateException(LogCodes.WPH2012E, "", path, "Cannot intermix " + schemaType
          + " arrays by index and arrays by query in the same masking rule");
    }
  }

  /**
   * Logs error
   *
   * @param messageId
   * @param rule
   * @param key
   * @param message
   */
  private void generateException(String messageId, String rule, String key, String message) {
    switch (messageId) {
      case "WPH2012E":
        log.logError(LogCodes.WPH2012E, message, rule);
        break;
      case "WPH2013E":
        log.logError(LogCodes.WPH2013E, message, rule, key);
        break;
      default: // Use the generic IPV-Core error code
        log.logError(LogCodes.WPH1013E, message);
        break;
    }
    throw new IllegalArgumentException(message);
  }

  /**
   * @param resourceType
   * @param resourceId
   * @param node
   * @param unMaskedNode
   * @param valueNode
   * @param path
   * @param maskingAction
   */
  // TODO test this with arrays
  private List<MaskingActionInputIdentifier> maskFinalPathComplex(String resourceType,
      String resourceId, JsonNode node, JsonNode valueNode, String path,
      FHIRResourceMaskingAction maskingAction, JsonNode root) {
    List<MaskingActionInputIdentifier> returnRecords = new ArrayList<>();
    if (valueNode == null) {
      return returnRecords;
    }
    AbstractComplexMaskingProvider<JsonNode> abstractComplexMaskingProvider =
        maskingAction.getAbstractComplexMaskingProvider();

    if (valueNode.isObject()) {
      returnRecords.add(new MaskingActionInputIdentifier(abstractComplexMaskingProvider, valueNode,
          node, path, resourceType, resourceId, root));
    } else if (valueNode.isArray()) {

      Iterator<JsonNode> items = valueNode.elements();

      while (items.hasNext()) {
        JsonNode item = items.next();
        returnRecords.add(new MaskingActionInputIdentifier(abstractComplexMaskingProvider, item,
            node, null, resourceType, resourceId, root));
      }
    }
    return returnRecords;
  }

  /**
   * Given a masking provider, gets the node that need to be masked and set them up for masking
   * action
   *
   * @param resourceType
   * @param resourceId
   * @param node
   * @param unMaskedNode
   * @param valueNode
   * @param path
   * @param maskingAction
   */
  private List<MaskingActionInputIdentifier> maskFinalPathSimple(String resourceType,
      String resourceId, JsonNode node, JsonNode valueNode, String path,
      FHIRResourceMaskingAction maskingAction, JsonNode root) {
    List<MaskingActionInputIdentifier> returnRecords = new ArrayList<>();
    MaskingProvider maskingProvider = maskingAction.getMaskingProvider();

    if (valueNode == null) {
      return returnRecords;
    }

    if (valueNode.isObject()) {
      return returnRecords;
    }

    if (valueNode.isArray()) {
      Iterator<JsonNode> items = valueNode.elements();
      ArrayNode arrayNode = new ArrayNode(JsonNodeFactory.instance);

      while (items.hasNext()) {
        JsonNode item = items.next();

        if (item.isNull() || item.isObject() || item.isArray()) {
          arrayNode.add(item);
          continue;
        }

        returnRecords.add(new MaskingActionInputIdentifier(maskingProvider, item, valueNode, path,
            resourceType, resourceId, root));
      }

    } else {
      if (!valueNode.isNull()) {
        if (checkIfArrayPath(path)) {
          String arrayNodePath = path.substring(0, path.indexOf("["));
          node = node.get(arrayNodePath);
        }
        returnRecords.add(new MaskingActionInputIdentifier(maskingProvider, valueNode, node, path,
            resourceType, resourceId, root));
      }
    }
    return returnRecords;
  }

  /**
   * Given a masking configuration, this gets the providers and the nodes to mask Depending on the
   * masking action, it sets or puts the nodes accordingly
   *
   * @param resourceType
   * @param resourceId
   * @param node
   * @param unMaskedNode
   * @param paths
   * @param pathIndex
   * @param maskingAction
   * @param actualFullPath
   * @return
   */
  private List<MaskingActionInputIdentifier> maskNode(String resourceType, String resourceId,
      JsonNode node, String[] paths, int pathIndex, FHIRResourceMaskingAction maskingAction,
      String actualFullPath, JsonNode root) {

    List<MaskingActionInputIdentifier> returnList = new ArrayList<>();
    // Extract path
    String path = (pathIndex < paths.length) ? paths[pathIndex] : "";

    // Get node based on provided path
    JsonNode valueNode = (!path.equals("")) ? processPath(path, node) : node;

    if (valueNode == null) {
      return returnList;
    }

    // There is more paths to process
    if (pathIndex < (paths.length - 1)) {
      if (valueNode.isArray()) {
        Iterator<JsonNode> items = valueNode.elements();
        while (items.hasNext()) {
          returnList.addAll(maskNode(resourceType, resourceId, items.next(), paths, pathIndex + 1,
              maskingAction, actualFullPath, root));
        }
      } else if (valueNode.isObject()) {
        returnList.addAll(maskNode(resourceType, resourceId, valueNode, paths, pathIndex + 1,
            maskingAction, actualFullPath, root));
      }
    } else {
      if (maskingAction.getAbstractComplexMaskingProvider() != null) {
        returnList.addAll(maskFinalPathComplex(resourceType, resourceId, node, valueNode, path,
            maskingAction, root));
      } else {
        returnList.addAll(maskFinalPathSimple(resourceType, resourceId, node, valueNode, path,
            maskingAction, root));
      }
    }

    return returnList;
  }

  /**
   * Given a parent node and the next path, this method checks if it's a path to an array element or
   * not using regex then gets the correct child node to return
   *
   * @param path : immediate path to next node
   * @param node : parent node
   * @return: child node from node.get(path-after-processing)
   */
  private JsonNode processPath(String path, JsonNode node) {
    // If we call get(path) for an array with an index,
    // a null value is returned and the rest of maskNode is ignored
    JsonNode subNode = null;

    if (node == null || node.isNull() || path.isEmpty()) {
      return subNode;
    }

    // Pattern = arrayName[arrayIndex]
    // This pattern checks if the path is an array with a numeric index
    // specified
    // The formats that filtered through here would be:
    // - array[i]: only process the element at index i
    // - array: without the []
    // - item: a path to a non-array element
    try {
      if (path.matches("(\\w+)(\\[)([0-9]+)(\\])")) {
        String[] parts = path.split("\\[|\\]");
        String arrayName = parts[0];
        int arrayIndex = Integer.parseInt(parts[1]);
        JsonNode tempNode = node.get(arrayName);
        if (tempNode.isArray() && tempNode.has(arrayIndex)) {
          subNode = tempNode.get(arrayIndex);
        }
        // else the item was not actually an array or index was not
        // found
        // therefore, return null (subNode still unmodified and = null
        // at this point)
      } else {
        // If the path is requesting a non-array node
        if (path.equals("__ALL__")) {
          subNode = node;
        } else {
          subNode = node.get(path);
        }
      }
    } catch (Exception e) { // Including IndexOutOfBoundsException
      subNode = null;
    }
    return subNode;
  }

  /**
   * This method parses the String path to get to the JSON node. E.g. given path =
   * "/extension[1]/value", return the node at: root.get("extension").get(1).get("value") This
   * assume formats of the path items are array[index], array or non-array
   *
   * <p>
   * Input:
   *
   * @param path : a String path to the node e.g. /extension[1]/value
   * @param root : a JSON node for the root resource e.g. Group
   *        <p>
   *        Output:
   * @return The JsonNode found at that path
   */
  private JsonNode getNodeFromPath(String path, JsonNode root) {
    String[] items = path.split("\\/");
    JsonNode subNode = root;
    for (String item : items) {
      if (!item.isEmpty()) {
        subNode = processPath(item, subNode);
      }
    }

    return subNode;
  }

  /**
   * This method checks if the String path (e.g. /<type>/Resource/path/to/element) represents an
   * array with []
   *
   * @param path
   * @return true if array, false otherwise
   */
  private boolean checkIfArrayPath(String path) {
    // TODO check if contains ==
    return (path.matches("(.*)(\\[)(.*)(\\])(.*)") /*
                                                    * || path.contains("==")
                                                    */);
  }

  /**
   * This method traverses the path from the root "node" using the existing getNodeFromPath method.
   * It then checks if the last element is an array
   *
   * <p>
   * Input: See comments for the getNodeFromPath method
   *
   * <p>
   * Output:
   *
   * @return true if the parsed node is an array
   * @return false otherwise
   */
  private boolean checkIfArrayNode(String path, JsonNode node) {
    JsonNode subNode = getNodeFromPath(path, node);
    return (subNode != null && subNode.isArray());
  }

  /**
   * This method returns the size of the node at the path ONLY IF it is an array
   *
   * <p>
   * Input: See comments for the getNodeFromPath method
   *
   * <p>
   * Output:
   *
   * @return size of node if it is an array
   * @return -1 otherwise
   */
  private int getSizeArray(String path, JsonNode node) {
    JsonNode subNode = getNodeFromPath(path, node);
    return (subNode != null && subNode.isArray()) ? subNode.size() : -1;
  }

  /**
   * This method returns a list of the paths that should be masked. e.g. given path =
   * "/extension[*]/value", it will break it down to a list of paths: "/extension[0]/value",
   * "/extension[1]/value", ... , "/extension[size-1]/value". So we know exactly which array
   * elements should be masked in the output
   *
   * <p>
   * If the input is for non-array, it will return the same path
   *
   * @param paths : from maskingAction.getPaths(), an array of the path split by '/'
   * @param actualPath : concatenated paths, accumulated form the recursive call
   * @param itemIndex : index of which path[] element is currently processed (for recursive call)
   * @param allActualPaths : the ArrayList, also accumulated from the recursive call
   * @param node : the root node for the resource
   * @return an ArrayList of the distinct paths to process
   */
  private ArrayList<String> getActualPaths(String[] paths, String actualPath, int itemIndex,
      ArrayList<String> allActualPaths, JsonNode node) {
    for (int i = itemIndex; i < paths.length; i++) {
      String item = paths[i];
      if (item.isEmpty()) {
        continue;
      }

      String regex = "(\\w+)(\\[)(.*)(\\])";
      Pattern pattern = Pattern.compile(regex);
      Matcher matcher = pattern.matcher(item);
      if (matcher.find()) {
        // Extract info from path
        String arrayName = matcher.group(1);
        String arrayIndex = matcher.group(3);
        actualPath += "/" + arrayName;

        // Get rid of whitespaces
        arrayIndex = arrayIndex.replaceAll("\\s", "");

        // Allowed array formats:
        // - array[*]: process all elements
        // - array[i]: only process the element at index i
        // - array[start_ind, end_ind] process start_ind, start_ind+1,
        // …, end_ind elements
        // - array[start_ind, *]: process start_ind, start_ind+1, until
        // end of array
        // - array[{1,3,5,17}]: process the elements at *specific* (the
        // specified) positions of the array: 1, 3, 5, 17.
        // Note: the pattern does not accept negative array indices
        try {
          // Check if the node truly represents an array
          // If index is '*', then mask the whole array
          // e.g: array[*]
          if (arrayIndex.matches("\\*+")) {
            // Sending + "[*]" to know it's an array
            for (int j = 0; j < getSizeArray(actualPath, node); j++) {
              allActualPaths =
                  getActualPaths(paths, actualPath + "[" + j + "]", i + 1, allActualPaths, node);
            }
            return allActualPaths;
          }

          // If a certain index is specified,
          // only mask that element and not the whole array
          // e.g: array[i]
          else if (arrayIndex.matches("[0-9]+")) {
            actualPath += "[" + arrayIndex + "]";
          }

          // If a range of indices is provided
          // e.g.: array [<start-index>,<end-index>] or
          // [<start-index>,*]
          else if (arrayIndex.matches("([0-9]+)(,)([0-9]+|\\*+)")) {
            // Get size of array
            int size = getSizeArray(actualPath, node);
            // Split the indices
            String parts[] = arrayIndex.split(",");
            // If there is an index provided AND it's a number AND
            // it's positive ? use the index: else set to default
            // min = 0, max = (size of node - 1)
            int minIndex =
                (parts.length > 0 && parts[0].matches("[0-9]+") && Integer.parseInt(parts[0]) >= 0)
                    ? Integer.parseInt(parts[0])
                    : 0;
            int maxIndex =
                (parts.length > 1 && parts[1].matches("[0-9]+") && Integer.parseInt(parts[1]) >= 0)
                    ? Integer.parseInt(parts[1])
                    : size - 1;
            for (int j = minIndex; j <= maxIndex && j < size; j++) {
              allActualPaths =
                  getActualPaths(paths, actualPath + "[" + j + "]", i + 1, allActualPaths, node);
            }
            return allActualPaths;
          }

          // If a list of indices is provided
          // e.g.: array[{1,3,5,17}]
          else if (arrayIndex.matches("(\\{)([0-9]+,?)+(\\})")) {
            // Get rid of { and }
            arrayIndex = arrayIndex.substring(1, arrayIndex.length() - 1);
            // Split the list by comma
            String[] indices = arrayIndex.split(",");
            for (String index : indices) {
              // If it's a number
              if (index.matches("[0-9]+")) {
                allActualPaths = getActualPaths(paths, actualPath + "[" + index + "]", i + 1,
                    allActualPaths, node);
              }
            }
            return allActualPaths;
          }

          // Invalid array format provided; return empty list
          else {
            return new ArrayList<String>();
          }
        } catch (Exception e) { // Including IndexOutOfBoundsException
          // Return empty list
          return new ArrayList<String>();
        }
      } else {
        actualPath += "/" + item;
        // If item is an array but not labeled as such. i.e.
        // extension/extension.
        // should add the separate elements to the path (to accurately
        // record when element gets masked)
        if (checkIfArrayNode(actualPath, node)) {
          for (int j = 0; j < getSizeArray(actualPath, node); j++) {
            allActualPaths =
                getActualPaths(paths, actualPath + "[" + j + "]", i + 1, allActualPaths, node);
          }
          return allActualPaths;
        }
      }
    }
    allActualPaths.add(actualPath);

    return allActualPaths;
  }

  @Override
  public JsonNode mask(JsonNode identifier) {
    List<Tuple3<String, JsonNode, String>> inputList = new ArrayList<>();
    inputList.add(new Tuple3<String, JsonNode, String>("1", identifier,
        identifier.get("resourceType").asText()));
    return mask(inputList).get(0)._2();
  }

  public List<Tuple3<String, JsonNode, String>> mask(
      List<Tuple3<String, JsonNode, String>> maskList) {

    for (FHIRResourceMaskingAction maskingAction : this.maskingActionList) {
      List<MaskingActionInputIdentifier> listToMask = new ArrayList<>();
      for (Tuple3<String, JsonNode, String> unMaskedNode : maskList) {
        JsonNode idNode = unMaskedNode._2().get("id");
        String resourceId = "UNKNOWN";
        if (idNode != null && !idNode.isNull() && idNode.isTextual()) {
          resourceId = idNode.asText();
        }

        maskingAuditTrailList = new ArrayList<>();
        List<JsonNode> maskedConditionNamedNodes = new ArrayList<>();

        if (maskingAction.getShortRuleName().contains("==")) {
          // Rule contains array query condition.
          listToMask.addAll(processArrayQueryCondition(unMaskedNode._2(), maskingAction,
              maskedConditionNamedNodes, unMaskedNode._3(), resourceId));
        } else {
          ArrayList<String> allPathsInRecord = getActualPaths(maskingAction.getPaths(), "", 0,
              new ArrayList<String>(), unMaskedNode._2());

          for (String currentPath : allPathsInRecord) {
            String fullPath = currentPath;
            // Check if the element can be masked. Precedence:
            // isPartofAList > arrayAllRules

            // If there is more than 1 comma separated provider,
            // apply all regardless of other conditions
            if (maskingAction.getIsPartOfAList()
                || (checkIfArrayPath(currentPath) && (arrayAllRules)) ||

                // If represents an array and arrayAllRules true
                // OR the item has not been masked before, then mask.
                (!checkIfArrayPath(currentPath))) {

              // If does not represent an array & has not been
              // masked before
              // Remove the first '/' and split the path
              String cleanedCurrentPath =
                  (currentPath.startsWith("/")) ? currentPath.substring(1, currentPath.length())
                      : currentPath;

              String[] brokenDownPathElements = cleanedCurrentPath.split("\\/");

              // Mask the node and add message to audit trail
              listToMask.addAll(maskNode(unMaskedNode._3(), resourceId, unMaskedNode._2(),
                  brokenDownPathElements, 0, maskingAction, fullPath, unMaskedNode._2()));
            }
          }
        }
      }
      MaskingProvider currentProvider = maskingAction.getMaskingProvider();
      if (currentProvider != null) {
        maskingAction.getMaskingProvider().maskIdentifierBatch(listToMask);
      } else if (maskingAction.getAbstractComplexMaskingProvider() != null) {
        maskingAction.getAbstractComplexMaskingProvider().maskIdentifierBatch(listToMask);
      }
      /*
       * // sorting out maintain and masking list if (!isDefNoRuleRes()) { JsonNode result =
       * processWhiteListJson(maskList); return result; }
       */
    }
    return maskList;
  }

  /**
   * arrayQueryCondition processes masking rules for array nodes with condition of the form like
   * "--/type>/resourceType/arrayNode/valueNode(siblingNode==siblingValue).
   *
   * @param node : fhir or gen resource
   * @param maskingAction : object containing the rule and the corresponding provider information
   * @param maskedConditionNamedNodes : a list that keeps track of the data nodes that have been
   *        masked for specified condition names as opposed by the wild card (*==*) so they are not
   *        masked again.
   * @param resourceType - the type of the fhir or gen resource being processed
   * @param resourceId - the ID of the fhir or gen resource being processed
   */
  private List<MaskingActionInputIdentifier> processArrayQueryCondition(JsonNode node,
      FHIRResourceMaskingAction maskingAction, List<JsonNode> maskedConditionNamedNodes,
      String resourceType, String resourceId) {

    String fullPath = maskingAction.getFullRuleName();
    String[] paths = maskingAction.getPaths();

    /*
     * Currently, only array leaf nodes and a single condition is supported. This path is for array
     * nodes with query conditions. For example, a FullPath of the form :
     * "/telecom/value(system==phone)"
     */
    String arrayPath = "";
    String conditionName = null;
    String conditionValue = null;
    List<String> dataPathList = new ArrayList<>();
    /*
     * We go through two loops: The first loop goes through the paths elements to get to the leaf
     * node with the condition, and then determines the specified conditionName and conditionValue.
     */
    List<JsonNode> nodeList = new ArrayList<>();
    for (int i = 0; i < paths.length; i++) {

      /*
       * Goes through each paths elements to collect all the array nodes and the condition name and
       * value.
       */
      if (paths[i].contains("==")) {
        // This is the paths element that has the condition, get the
        // condition name and value
        String[] arrayElementAndCondition = paths[i].split("\\(");

        String arrayElement = arrayElementAndCondition[0].trim();
        String conditionELement = arrayElementAndCondition[1].trim().replace(")", "");
        String[] condition = conditionELement.split("==");
        conditionName = condition[0].trim();
        conditionValue = condition[1].trim();
        dataPathList.add(arrayElement);
      } else {
        /*
         * The Following code for each paths.i traverses the nodes and collects the child nodes,
         * then at the end sets nodeList to childNodeList which for the next loop nodeList becomes
         * the new parent.
         */
        arrayPath = paths[i];
        if (nodeList.isEmpty()) {
          // To avoid NullPointerException when calling
          // subNode.get(arrayPath);
          // With arrayPath = array[i] <== with an index
          // In the future need to use: JsonNode arrayNode =
          // processPath(arrayPath, node);
          JsonNode arrayNode = node.get(arrayPath);
          if (arrayNode == null || arrayNode.isNull()) {
            // The FHIR resource node does not have the array node
            // or its parent node.
            // The rule does not apply.
            break;
          }
          if (arrayNode.isObject()) {
            nodeList.add(arrayNode);
            continue;
          }
          for (JsonNode childNode : arrayNode) {
            nodeList.add(childNode);
          }

        } else {

          List<JsonNode> childNodeList = new ArrayList<>();
          for (JsonNode subNode : nodeList) {
            // To avoid NullPointerException when calling
            // subNode.get(arrayPath);
            // With arrayPath = array[i] <== with an index
            // For the future need to use: JsonNode arrayNode =
            // processPath(arrayPath, subNode);

            JsonNode arrayNode = subNode.get(arrayPath);
            if (arrayNode == null || arrayNode.isNull()) {
              // The resource node does not have the array
              // node or its parent node.
              // The rule does not apply.
              continue;
            }
            if (arrayNode.isObject()) {
              childNodeList.add(arrayNode);
            } else {
              for (JsonNode childNode : arrayNode) {
                childNodeList.add(childNode);
              }
            }
          }
          nodeList = childNodeList;
        }
      }
    }

    List<MaskingActionInputIdentifier> inputList = new ArrayList<>();
    /*
     * The second loop walks through the array resource node to find the leaf node with sibling that
     * matches the condition name and value determined in the first loop above. Note: the
     * conditionName of * and conditionValue of * are used as a wild card. *
     */
    for (JsonNode elementNode : nodeList) {
      /*
       * The rules are specified with specific condition names followed by the wild card * condition
       * name. For example: "--/fhir/Location/telecom/value(system==phone)":
       * {"default.masking.provider": "PHONE"}, "--/gen/Location/telecom/value(system==email)":
       * {"default.masking.provider": "EMAIL"}, "--/fhir/Location/telecom/value(system==*)": {
       * "default.masking.provider": "RANDOM" }, "--/gen/Location/telecom/value(*==*)": {
       * "default.masking.provider": "RANDOM" }
       *
       * We mask the nodes for the specific conditions with their corresponding masking providers
       * first, and then mask the remaining nodes, if any, if there is a wild card rule (condition
       * name of *) is defined.
       */
      if ("*".equals(conditionName) && !maskedConditionNamedNodes.contains(elementNode)) {
        // Process the node, if it has not already been
        // masked by a specified condition name.
        String[] dataPath = dataPathList.toArray(new String[dataPathList.size()]);
        inputList.addAll(maskNode(resourceType, resourceId, elementNode, dataPath, 0, maskingAction,
            fullPath, node));

      } else if (elementNode.has(conditionName)) {
        JsonNode key = elementNode.get(conditionName);
        String value = key.asText();
        if (value.equals(conditionValue)
            || ("*".equals(conditionValue) && !maskedConditionNamedNodes.contains(elementNode))) {
          String[] dataPath = dataPathList.toArray(new String[dataPathList.size()]);
          maskedConditionNamedNodes.add(elementNode);
          inputList.addAll(maskNode(resourceType, resourceId, elementNode, dataPath, 0,
              maskingAction, fullPath, node));
        }
      }
    }

    return inputList;
  }

  public List<String> getMaskingAuditTrailList() {
    return maskingAuditTrailList;
  }

  public boolean isDefNoRuleRes() {
    return defNoRuleRes;
  }

  public void setDefNoRuleRes(boolean defNoRuleRes) {
    this.defNoRuleRes = defNoRuleRes;
  }

  @Override
  public void maskIdentifierBatch(List<MaskingActionInputIdentifier> identifiers) {
    // TODO Auto-generated method stub
    return;
  }
}
