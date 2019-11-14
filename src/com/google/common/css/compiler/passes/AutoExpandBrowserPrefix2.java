/*
 * Copyright 2015 Google Inc.
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

package com.google.common.css.compiler.passes;

import com.google.common.collect.ImmutableList;
import com.google.common.css.compiler.ast.CssCommentNode;
import com.google.common.css.compiler.ast.CssCompilerPass;
import com.google.common.css.compiler.ast.CssDeclarationBlockNode;
import com.google.common.css.compiler.ast.CssDeclarationNode;
import com.google.common.css.compiler.ast.CssFunctionNode;
import com.google.common.css.compiler.ast.CssMixinDefinitionNode;
import com.google.common.css.compiler.ast.CssNode;
import com.google.common.css.compiler.ast.CssPropertyNode;
import com.google.common.css.compiler.ast.CssPropertyValueNode;
import com.google.common.css.compiler.ast.CssValueNode;
import com.google.common.css.compiler.ast.DefaultTreeVisitor;
import com.google.common.css.compiler.ast.MutatingVisitController;
import java.util.ArrayList;
import java.util.List;

/**
 * A compiler pass that automatically detects certain properties that need additional
 * browser specific property declarations, and adds them.
 * The properties to be matched for expansion are provided by the {@link BrowserPrefixGenerator}.
 *
 * <p>This mechanism is an alternative to using conventional mixins.
 * Problems with conventional mixins:
 * - developers have to always remember to use the mixin consistently
 * - they have to go find the appropriate mixins
 * - the framework has to to verify the code and ensure mixins were used correctly
 * Automation addresses all of the above issues.
 *
 * <p>Currently three most common cases are handled:
 * #1 Matching and replacing only the property name. Eg. flex-grow: VALUE;
 * #2 Matching property name and value, replacing the value. Eg. display: flex;
 * #3 Matching property name and value where value is a function, replacing the function name.
 *    Eg. background-image: linear-gradient(ARGS);
 *
 */
public class AutoExpandBrowserPrefix2 extends DefaultTreeVisitor implements CssCompilerPass {

  private final MutatingVisitController visitController;
  private boolean inDefMixinBlock;
  private boolean remove;

  public AutoExpandBrowserPrefix2(MutatingVisitController visitController, Boolean remove) {
    this.visitController = visitController;
    this.remove = remove;
  }

  @Override
  public boolean enterMixinDefinition(CssMixinDefinitionNode node) {
    inDefMixinBlock = true;
    return true;
  }

  @Override
  public void leaveMixinDefinition(CssMixinDefinitionNode node) {
    inDefMixinBlock = false;
  }

  @Override
  public boolean enterDeclaration(CssDeclarationNode declaration) {
    // Do not auto expand properties inside @defmixin blocks.
    // To enable compatibility with existing mixin expansion, don't apply the rules to the
    // mixin definitions. This leaves the mixin expansion unaffected.
    if (inDefMixinBlock) {
      return true;
    }
    if (this.remove) {
      if (declaration.autoExpanded) {
        visitController.removeCurrentNode();
      }
    } else {
      if (!declaration.autoExpanded) {
        visitController.removeCurrentNode();
      } else {
        declaration.setComments(new ArrayList<CssCommentNode>());
      }
    }

    // if (replacements.size() > 1) {
    //   visitController.replaceCurrentBlockChildWith(
    //       replacements, false /* visitTheReplacementNodes */);
    //   break; // found a match, don't need to look for more
    // }
    return true;
  }

  protected ImmutableList<CssDeclarationNode> getNonFunctionValueMatches(
      BrowserPrefixRule rule, CssDeclarationNode declaration) {
    // Ensure that the property value matches exactly.
    if (!(declaration.getPropertyValue().getChildren().size() == 1
        && rule.getMatchPropertyValue()
            .equals(declaration.getPropertyValue().getChildAt(0).getValue()))) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<CssDeclarationNode> replacements = ImmutableList.builder();
    // TODO(user): Maybe support multiple values for non-function value-only expansions.
    // this is not applicable since there are no non-function value-only expansions.
    for (CssPropertyValueNode ruleValueNode : rule.getValueOnlyExpansionNodes()) {
      // For valueOnlyExpansionNodes the property name comes from the declaration.
      CssDeclarationNode expansionNode =
          new CssDeclarationNode(
              declaration.getPropertyName(),
              ruleValueNode.deepCopy(),
              declaration.getSourceCodeLocation());
      expansionNode.appendComment(new CssCommentNode("/* @alternate */", null));
      expansionNode.autoExpanded = true;
      replacements.add(expansionNode);
    }

    // check if the block has a node with the same name, e.g., display: -ms-flex; display: flex
    Boolean includesName = BlockContainsProp((CssDeclarationBlockNode) declaration.getParent(),
        rule.getMatchPropertyName(),
      declaration);

    for (CssDeclarationNode ruleExpansionNode : rule.getExpansionNodes()) {
      if (includesName) {
        Boolean includes = BlockContainsPropWithValue(
          (CssDeclarationBlockNode) declaration.getParent(),
          ruleExpansionNode.getPropertyName(), ruleExpansionNode.getPropertyValue(), declaration);
        if (includes) {
          continue;
        }
      }
      CssDeclarationNode expansionNode = ruleExpansionNode.deepCopy();
      expansionNode.setSourceCodeLocation(declaration.getSourceCodeLocation());
      replacements.add(expansionNode);
    }
    return replacements.build();
  }

  private ImmutableList<CssDeclarationNode> getOtherMatches(
      CssDeclarationNode declaration, BrowserPrefixRule rule) {
    CssValueNode matchValueNode = declaration.getPropertyValue().getChildAt(0);
    if (!(matchValueNode instanceof CssFunctionNode)) {
      return ImmutableList.of();
    }
    CssFunctionNode matchFunctionNode = (CssFunctionNode) matchValueNode;
    if (!matchFunctionNode.getFunctionName().equals(rule.getMatchPropertyValue())) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<CssDeclarationNode> replacements = ImmutableList.builder();
    for (CssDeclarationNode ruleExpansionNode : rule.getExpansionNodes()) {
      CssDeclarationNode expansionNode = ruleExpansionNode.deepCopy();
      CssValueNode expandValueNode = expansionNode.getPropertyValue().getChildAt(0);
      CssFunctionNode expandFunctionNode = (CssFunctionNode) expandValueNode;
      expandFunctionNode.setArguments(matchFunctionNode.getArguments().deepCopy());
      expansionNode.setSourceCodeLocation(declaration.getSourceCodeLocation());
      replacements.add(expansionNode);
    }
    return replacements.build();
  }

  /**
   * Returns true if the value node is a function and matches the rule.
   */
  private static boolean matchesValueOnlyFunction(CssValueNode declarationValueNode,
      BrowserPrefixRule rule) {
    return (declarationValueNode instanceof CssFunctionNode)
        && ((CssFunctionNode) declarationValueNode).getFunctionName()
            .equals(rule.getMatchPropertyValue());
  }

  /**
   * Returns true if the rule is value-only and at least one function value in the declaration
   * matches the rule.
   */
  private static boolean hasMatchingValueOnlyFunction(CssDeclarationNode declaration,
      BrowserPrefixRule rule) {
    if (rule.getValueOnlyExpansionNodes().isEmpty()) {
      return false;
    }
    for (CssValueNode declarationValueNode : declaration.getPropertyValue().getChildren()) {
      if (matchesValueOnlyFunction(declarationValueNode, rule)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the value-only function expansion for this declaration and rule. For each value-only
   * expansion rule we can match 0 or more values.
   * For example: margin: calc(X) calc(Y); -> margin: -webkit-calc(X) -webkit-calc(Y);
   */
  private static ImmutableList<CssDeclarationNode> expandMatchingValueOnlyFunctions(
      CssDeclarationNode declaration, BrowserPrefixRule rule) {
    ImmutableList.Builder<CssDeclarationNode> expansionNodes = ImmutableList.builder();
    for (CssPropertyValueNode ruleValueNode : rule.getValueOnlyExpansionNodes()) {
      List<CssValueNode> expansionNodeValues =
          new ArrayList<>(declaration.getPropertyValue().numChildren());
      for (CssValueNode declarationValueNode : declaration.getPropertyValue().getChildren()) {
        if (matchesValueOnlyFunction(declarationValueNode, rule)) {
          CssFunctionNode declarationFunctionNode = (CssFunctionNode) declarationValueNode;
          CssFunctionNode expansionFunctionNode =
              (CssFunctionNode) ruleValueNode.getChildAt(0).deepCopy();
          expansionFunctionNode.setArguments(declarationFunctionNode.getArguments().deepCopy());
          expansionNodeValues.add(expansionFunctionNode);
        } else {
          expansionNodeValues.add(declarationValueNode.deepCopy());
        }
      }
      // For valueOnlyExpansionNodes the property name comes from the declaration.
      CssPropertyValueNode expansionValues = new CssPropertyValueNode(expansionNodeValues);
      CssDeclarationNode expansionNode =
          new CssDeclarationNode(
              declaration.getPropertyName(), expansionValues, declaration.getComments(),
          declaration.getSourceCodeLocation());
      expansionNode.autoExpanded = true;

      expansionNode.appendComment(new CssCommentNode("/* @alternate */", null));
      expansionNodes.add(expansionNode);
    }
    return expansionNodes.build();
  }

  @Override
  public void runPass() {
    visitController.startVisit(this);
  }

  private static boolean BlockContainsProp(CssDeclarationBlockNode node, String matchName,
    CssDeclarationNode declaration) {
    for (CssNode decl : node.getChildren()) {
      CssDeclarationNode d = (CssDeclarationNode) decl;
      if (d.equals(declaration)) {
        continue;
      }
      Boolean nameEquals = d.getPropertyName().getValue().equals(matchName);
      if (nameEquals) return true;
    }
    return false;
  }
  // parent:CssDeclarationBlockNode
  private static boolean BlockContainsPropWithValue(CssDeclarationBlockNode node, CssPropertyNode propName,
  CssPropertyValueNode propertyValue, CssDeclarationNode declaration) {
    for (CssNode decl : node.getChildren()) {
      CssDeclarationNode d = (CssDeclarationNode) decl;
      if (d.equals(declaration)) {
        continue;
      }
      Boolean nameEquals = d.getPropertyName().getValue().equals(propName.getValue());
      if (!nameEquals) continue;
      Boolean valueEquals = d.getPropertyValue().toString().equals(propertyValue.toString());
      if (valueEquals) return true;
    }
    return false;
  }

  private static boolean BlockContainsPropName(CssDeclarationBlockNode node, CssPropertyNode propName,
      CssDeclarationNode declaration) {
    for (CssNode decl : node.getChildren()) {
      CssDeclarationNode d = (CssDeclarationNode) decl;
      if (d.equals(declaration)) continue;
      if (d.getPropertyName().getValue().equals(propName.getValue())) {
        return true;
      }
    }
    return false;
  }
}
