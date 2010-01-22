/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.annotator;

import com.intellij.codeInsight.ClassUtil;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyBundle;
import org.jetbrains.plugins.groovy.annotator.intentions.*;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.DynamicFix;
import org.jetbrains.plugins.groovy.codeInspection.GroovyImportsTracker;
import org.jetbrains.plugins.groovy.config.GroovyConfigUtils;
import org.jetbrains.plugins.groovy.highlighter.DefaultHighlighter;
import org.jetbrains.plugins.groovy.intentions.utils.DuplicatesUtil;
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.*;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.lexer.TokenSets;
import org.jetbrains.plugins.groovy.lang.psi.*;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifierList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentLabel;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrOpenBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrBreakStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrContinueStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrFlowInterruptingStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrReturnStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.clauses.GrForInClause;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMember;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.packaging.GrPackageDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeParameter;
import org.jetbrains.plugins.groovy.lang.psi.api.util.GrVariableDeclarationOwner;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrClosureType;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GroovyScriptClass;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;
import org.jetbrains.plugins.groovy.lang.resolve.processors.PropertyResolverProcessor;
import org.jetbrains.plugins.groovy.overrideImplement.quickFix.ImplementMethodsQuickFix;

import java.util.*;

/**
 * @author ven
 */
public class GroovyAnnotator extends GroovyElementVisitor implements Annotator {
  private static final Logger LOG = Logger.getInstance("org.jetbrains.plugins.groovy.annotator.GroovyAnnotator");

  private AnnotationHolder myHolder;

  private static boolean isDocCommentElement(PsiElement element) {
    if (element == null) return false;
    ASTNode node = element.getNode();
    return node != null && PsiTreeUtil.getParentOfType(element, GrDocComment.class) != null || element instanceof GrDocComment;
  }

  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (element instanceof GroovyPsiElement) {
      myHolder = holder;
      ((GroovyPsiElement)element).accept(this);
      myHolder = null;
    }
  }

  @Override
  public void visitElement(GroovyPsiElement element) {
    if (element.getParent() instanceof GrDocReferenceElement) {
      checkGrDocReferenceElement(myHolder, element);
    }
    else {
      final ASTNode node = element.getNode();
      if (!(element instanceof PsiWhiteSpace) &&
          !GroovyTokenTypes.COMMENT_SET.contains(node.getElementType()) &&
          element.getContainingFile() instanceof GroovyFile &&
          !isDocCommentElement(element)) {
        GroovyImportsTracker.getInstance(element.getProject()).markFileAnnotated((GroovyFile)element.getContainingFile());
      }
    }
  }

  @Override
  public void visitCodeReferenceElement(GrCodeReferenceElement refElement) {
    final PsiElement parent = refElement.getParent();
    GroovyResolveResult resolveResult = refElement.advancedResolve();
    highlightAnnotation(myHolder, refElement, resolveResult);
    registerUsedImport(refElement, resolveResult);
    highlightAnnotation(myHolder, refElement, resolveResult);
    if (refElement.getReferenceName() != null) {

      if (parent instanceof GrImportStatement && ((GrImportStatement)parent).isStatic() && refElement.multiResolve(false).length > 0) {
        return;
      }

      checkSingleResolvedElement(myHolder, refElement, resolveResult);
    }

  }

  @Override
  public void visitReferenceExpression(GrReferenceExpression referenceExpression) {
    GroovyResolveResult resolveResult = referenceExpression.advancedResolve();
    GroovyResolveResult[] results = referenceExpression.multiResolve(false); //cached
    for (GroovyResolveResult result : results) {
      registerUsedImport(referenceExpression, result);
    }

    PsiElement resolved = resolveResult.getElement();
    final PsiElement parent = referenceExpression.getParent();
    if (resolved != null) {
      if (resolved instanceof PsiMember) {
        highlightMemberResolved(myHolder, referenceExpression, ((PsiMember)resolved));
      }
      if (!resolveResult.isAccessible()) {
        String message = GroovyBundle.message("cannot.access", referenceExpression.getReferenceName());
        myHolder.createWarningAnnotation(referenceExpression.getReferenceNameElement(), message);
      }
      if (!resolveResult.isStaticsOK() && resolved instanceof PsiModifierListOwner) {
        if (!((PsiModifierListOwner)resolved).hasModifierProperty(PsiModifier.STATIC)) {
          myHolder.createWarningAnnotation(referenceExpression, GroovyBundle.message("cannot.reference.nonstatic", referenceExpression.getReferenceName()));
        }
      }
    }
    else {
      GrExpression qualifier = referenceExpression.getQualifierExpression();
      if (qualifier == null && isDeclarationAssignment(referenceExpression)) return;

      if (parent instanceof GrReferenceExpression && "class".equals(((GrReferenceExpression)parent).getReferenceName())) {
        checkSingleResolvedElement(myHolder, referenceExpression, resolveResult);
      }
    }

    if (parent instanceof GrCall) {
      if (resolved == null && results.length > 0) {
        resolved = results[0].getElement();
        resolveResult = results[0];
      }
      if (resolved instanceof PsiMethod && resolved.getUserData(GrMethod.BUILDER_METHOD) == null) {
        checkMethodApplicability(resolveResult, referenceExpression, myHolder);
      }
      else {
        checkClosureApplicability(resolveResult, referenceExpression.getType(), referenceExpression, myHolder);
      }
    }
    if (isDeclarationAssignment(referenceExpression) || resolved instanceof PsiPackage) return;

    if (resolved == null) {
      PsiElement refNameElement = referenceExpression.getReferenceNameElement();
      PsiElement elt = refNameElement == null ? referenceExpression : refNameElement;
      Annotation annotation = myHolder.createInfoAnnotation(elt, null);
      final GrExpression qualifier = referenceExpression.getQualifierExpression();
      if (qualifier == null) {
        if (!(parent instanceof GrCall)) {
          registerCreateClassByTypeFix(referenceExpression, annotation);
          registerAddImportFixes(referenceExpression, annotation);
        }
      } else {
        if (qualifier.getType() == null) {
          return;
        }
      }
      registerReferenceFixes(referenceExpression, annotation);
      annotation.setTextAttributes(DefaultHighlighter.UNRESOLVED_ACCESS);
    }
  }

  @Override
  public void visitTypeDefinition(GrTypeDefinition typeDefinition) {
    checkTypeDefinition(myHolder, typeDefinition);
    checkTypeDefinitionModifiers(myHolder, typeDefinition);

    final GrTypeDefinitionBody body = typeDefinition.getBody();
    if (body != null) checkDuplicateMethod(body.getGroovyMethods(), myHolder);
    checkImplementedMethodsOfClass(myHolder, typeDefinition);
  }

  @Override
  public void visitMethod(GrMethod method) {
    checkMethodDefinitionModifiers(myHolder, method);
    checkInnerMethod(myHolder, method);
  }

  @Override
  public void visitVariableDeclaration(GrVariableDeclaration variableDeclaration) {

    PsiElement parent = variableDeclaration.getParent();
    assert parent != null;

    PsiElement typeDef = parent.getParent();
    if (typeDef != null && typeDef instanceof GrTypeDefinition) {
      PsiModifierList modifiersList = variableDeclaration.getModifierList();
      checkAccessModifiers(myHolder, modifiersList);

      if (modifiersList.hasExplicitModifier(PsiModifier.VOLATILE) && modifiersList.hasExplicitModifier(PsiModifier.FINAL)) {
        myHolder.createErrorAnnotation(modifiersList, GroovyBundle.message("illegal.combination.of.modifiers.volatile.and.final"));
      }

      if (modifiersList.hasExplicitModifier(PsiModifier.NATIVE)) {
        myHolder.createErrorAnnotation(modifiersList, GroovyBundle.message("variable.cannot.be.native"));
      }

      if (modifiersList.hasExplicitModifier(PsiModifier.ABSTRACT)) {
        myHolder.createErrorAnnotation(modifiersList, GroovyBundle.message("variable.cannot.be.abstract"));
      }
    }
  }

  @Override
  public void visitVariable(GrVariable variable) {
    if (variable instanceof GrMember) {
      highlightMember(myHolder, ((GrMember)variable));
      checkStaticDeclarationsInInnerClass((GrMember)variable, myHolder);
    }
    PropertyResolverProcessor processor = new DuplicateVariablesProcessor(variable);
    final GroovyPsiElement duplicate =
      ResolveUtil.resolveExistingElement(variable, processor, GrVariable.class, GrReferenceExpression.class);

    if (duplicate instanceof GrVariable) {
      if (duplicate instanceof GrField && !(variable instanceof GrField)) {
        myHolder
          .createWarningAnnotation(variable.getNameIdentifierGroovy(), GroovyBundle.message("field.already.defined", variable.getName()));
      }
      else {
        final String key = duplicate instanceof GrField ? "field.already.defined" : "variable.already.defined";
        myHolder.createErrorAnnotation(variable.getNameIdentifierGroovy(), GroovyBundle.message(key, variable.getName()));
      }
    }
  }

  @Override
  public void visitAssignmentExpression(GrAssignmentExpression expression) {
    GrExpression lValue = expression.getLValue();
    if (!PsiUtil.mightBeLVlaue(lValue)) {
      myHolder.createErrorAnnotation(lValue, GroovyBundle.message("invalid.lvalue"));
    }
  }

  @Override
  public void visitReturnStatement(GrReturnStatement returnStatement) {
    final GrExpression value = returnStatement.getReturnValue();
    if (value != null) {
      final PsiType type = value.getType();
      if (type != null) {
        final GrParametersOwner owner = PsiTreeUtil.getParentOfType(returnStatement, GrMethod.class, GrClosableBlock.class);
        if (owner instanceof PsiMethod) {
          final PsiMethod method = (PsiMethod)owner;
          if (method.isConstructor()) {
            myHolder.createErrorAnnotation(value, GroovyBundle.message("cannot.return.from.constructor"));
          }
          else {
            final PsiType methodType = method.getReturnType();
            if (methodType != null) {
              if (PsiType.VOID.equals(methodType)) {
                myHolder.createErrorAnnotation(value, GroovyBundle.message("cannot.return.from.void.method"));
              }
            }
          }
        }
      }
    }
  }

  @Override
  public void visitListOrMap(GrListOrMap listOrMap) {
    final Map<GrNamedArgument, List<GrNamedArgument>> map = DuplicatesUtil.factorDuplicates(listOrMap.getNamedArguments(), new TObjectHashingStrategy<GrNamedArgument>() {
      public int computeHashCode(GrNamedArgument arg) {
        final GrArgumentLabel label = arg.getLabel();
        if (label == null) return 0;
        final String name = label.getName();
        if (name == null) return 0;
        return name.hashCode();
      }

      public boolean equals(GrNamedArgument arg1, GrNamedArgument arg2) {
        final GrArgumentLabel label1 = arg1.getLabel();
        final GrArgumentLabel label2 = arg2.getLabel();
        if (label1 == null || label2 == null) {
          return label1 == null && label2 == null;
        }
        final String name1 = label1.getName();
        final String name2 = label2.getName();
        if (name1 == null || name2 == null) {
          return name1 == null && name2 == null;
        }
        return name1.equals(name2);
      }
    });

    processDuplicates(map, myHolder);
  }

  @Override
  public void visitNewExpression(GrNewExpression newExpression) {
    if (newExpression.getArrayCount() > 0) return;
    GrCodeReferenceElement refElement = newExpression.getReferenceElement();
    if (refElement == null) return;
    final PsiElement element = refElement.resolve();
    if (element instanceof PsiClass) {
      PsiClass clazz = (PsiClass)element;
      if (clazz.hasModifierProperty(PsiModifier.ABSTRACT)) {
        if (newExpression.getAnonymousClassDefinition() == null) {
          String message = clazz.isInterface()
                           ? GroovyBundle.message("cannot.instantiate.interface", clazz.getName())
                           : GroovyBundle.message("cannot.instantiate.abstract.class", clazz.getName());
          myHolder.createErrorAnnotation(refElement, message);
        }
        return;
      }
      if (newExpression.getQualifier() != null) {
        if (clazz.hasModifierProperty(PsiModifier.STATIC)) {
          myHolder.createErrorAnnotation(newExpression, GroovyBundle.message("qualified.new.of.static.class"));
        }
      } else {
        final PsiClass outerClass = clazz.getContainingClass();
        if (com.intellij.psi.util.PsiUtil.isInnerClass(clazz) && !PsiUtil.hasEnclosingInstanceInScope(outerClass, newExpression, true)) {
          myHolder.createErrorAnnotation(newExpression, GroovyBundle.message("cannot.reference.nonstatic", clazz.getQualifiedName()));
        }
      }
    }

    final GroovyResolveResult constructorResolveResult = newExpression.resolveConstructorGenerics();
    if (constructorResolveResult.getElement() != null) {
      checkMethodApplicability(constructorResolveResult, refElement, myHolder);
      final GrArgumentList argList = newExpression.getArgumentList();
      if (argList != null && argList.getExpressionArguments().length == 0) checkDefaultMapConstructor(myHolder, argList);
    }
    else {
      final GroovyResolveResult[] results = newExpression.multiResolveConstructor();
      final GrArgumentList argList = newExpression.getArgumentList();
      PsiElement toHighlight = argList != null ? argList : refElement.getReferenceNameElement();

      if (results.length > 0) {
        String message = GroovyBundle.message("ambiguous.constructor.call");
        myHolder.createWarningAnnotation(toHighlight, message);
      }
      else {
        if (element instanceof PsiClass) {
          //default constructor invocation
          PsiType[] argumentTypes = PsiUtil.getArgumentTypes(refElement, true, true);
          if (argumentTypes != null && argumentTypes.length > 0) {
            String message = GroovyBundle.message("cannot.find.default.constructor", ((PsiClass)element).getName());
            myHolder.createWarningAnnotation(toHighlight, message);
          }
          else checkDefaultMapConstructor(myHolder, argList);
        }
      }
    }
  }

  @Override
  public void visitDocMethodReference(GrDocMethodReference reference) {
    checkGrDocMemberReference(reference, myHolder);
  }

  @Override
  public void visitDocFieldReference(GrDocFieldReference reference) {
    checkGrDocMemberReference(reference, myHolder);
  }

  @Override
  public void visitConstructorInvocation(GrConstructorInvocation invocation) {
    final GroovyResolveResult resolveResult = invocation.resolveConstructorGenerics();
    if (resolveResult != null) {
      checkMethodApplicability(resolveResult, invocation.getThisOrSuperKeyword(), myHolder);
    }
    else {
      final GroovyResolveResult[] results = invocation.multiResolveConstructor();
      final GrArgumentList argList = invocation.getArgumentList();
      if (results.length > 0) {
        String message = GroovyBundle.message("ambiguous.constructor.call");
        myHolder.createWarningAnnotation(argList, message);
      }
      else {
        final PsiClass clazz = invocation.getDelegatedClass();
        if (clazz != null) {
          //default constructor invocation
          PsiType[] argumentTypes = PsiUtil.getArgumentTypes(invocation.getThisOrSuperKeyword(), true, true);
          if (argumentTypes != null && argumentTypes.length > 0) {
            String message = GroovyBundle.message("cannot.find.default.constructor", clazz.getName());
            myHolder.createWarningAnnotation(argList, message);
          }
        }
      }
    }
  }

  @Override
  public void visitBreakStatement(GrBreakStatement breakStatement) {
    checkFlowInterruptStatement(breakStatement, myHolder);
  }

  @Override
  public void visitContinueStatement(GrContinueStatement continueStatement) {
    checkFlowInterruptStatement(continueStatement, myHolder);
  }

  @Override
  public void visitLabeledStatement(GrLabeledStatement labeledStatement) {
    final String name = labeledStatement.getLabelName();
    if (ResolveUtil.resolveLabeledStatement(name, labeledStatement, true) != null) {
      myHolder.createWarningAnnotation(labeledStatement.getLabel(), GroovyBundle.message("label.already.used", name));
    }
  }

  @Override
  public void visitPackageDefinition(GrPackageDefinition packageDefinition) {
    //todo: if reference isn't resolved it construct package definition
    final PsiFile file = packageDefinition.getContainingFile();
    assert file != null;

    PsiDirectory psiDirectory = file.getContainingDirectory();
    if (psiDirectory != null) {
      PsiPackage aPackage = JavaDirectoryService.getInstance().getPackage(psiDirectory);
      if (aPackage != null) {
        String packageName = aPackage.getQualifiedName();
        if (!packageDefinition.getPackageName().equals(packageName)) {
          final Annotation annotation = myHolder.createWarningAnnotation(packageDefinition, "wrong package name");
          annotation.registerFix(new ChangePackageQuickFix((GroovyFile)packageDefinition.getContainingFile(), packageName));
        }
      }
    }
    final GrModifierList modifierList = packageDefinition.getAnnotationList();
    checkAnnotationList(myHolder, modifierList, GroovyBundle.message("package.definition.cannot.have.modifiers"));
  }

  @Override
  public void visitSuperExpression(GrSuperReferenceExpression superExpression) {
    checkThisOrSuperReferenceExpression(superExpression, myHolder);
  }

  @Override
  public void visitThisExpression(GrThisReferenceExpression thisExpression) {
    checkThisOrSuperReferenceExpression(thisExpression, myHolder);
  }

  @Override
  public void visitLiteralExpression(GrLiteral literal) {
    String text = literal.getText();
    if (text.startsWith("'''")) {
      if (text.length() < 6 || !text.endsWith("'''")) {
        myHolder.createErrorAnnotation(literal, GroovyBundle.message("string.end.expected"));
      }
    }
    else if (text.startsWith("'")) {
      if (text.length() < 2 || !text.endsWith("'")) {
        myHolder.createErrorAnnotation(literal, GroovyBundle.message("string.end.expected"));
      }
    }
  }

  @Override
  public void visitForInClause(GrForInClause forInClause) {
    final GrVariable[] declaredVariables = forInClause.getDeclaredVariables();
    if (declaredVariables.length < 1) return;
    final GrVariable variable = declaredVariables[0];
    final GrModifierList modifierList = ((GrModifierList)variable.getModifierList());
    if (modifierList == null) return;
    final PsiElement[] modifiers = modifierList.getModifiers();
    for (PsiElement modifier : modifiers) {
      if (modifier instanceof PsiAnnotation) continue;
      final String modifierText = modifier.getText();
      if (PsiModifier.FINAL.equals(modifierText)) continue;
      if ("def".equals(modifierText)) continue;
      myHolder.createErrorAnnotation(modifier, GroovyBundle.message("not.allowed.modifier.in.forin", modifierText));
    }
  }

  @Override
  public void visitFile(GroovyFileBase file) {
    if (!file.isScript()) return;

    List<GrMethod> methods = new ArrayList<GrMethod>();

    for (GrTopLevelDefintion topLevelDefinition : file.getTopLevelDefinitions()) {
      if (topLevelDefinition instanceof GrMethod) {
        methods.add(((GrMethod)topLevelDefinition));
      }
    }

    checkDuplicateMethod(methods.toArray(new GrMethod[methods.size()]), myHolder);
  }

  @Override
  public void visitImportStatement(GrImportStatement importStatement) {
    checkAnnotationList(myHolder, importStatement.getAnnotationList(), GroovyBundle.message("import.statement.cannot.have.modifiers"));
  }

  private static void checkFlowInterruptStatement(GrFlowInterruptingStatement statement, AnnotationHolder holder) {
    final PsiElement label = statement.getLabelIdentifier();

    if (label != null) {
      final GrLabeledStatement resolved = statement.resolveLabel();
      if (resolved == null) {
        holder.createErrorAnnotation(label, GroovyBundle.message("undefined.label", statement.getLabelName()));
      }
    }

    final GrStatement targetStatement = statement.findTargetStatement();
    if (targetStatement == null) {
      if (statement instanceof GrContinueStatement && label == null) {
        holder.createErrorAnnotation(statement, GroovyBundle.message("continue.outside.loop"));
      }
      else if (statement instanceof GrBreakStatement && label == null) {
        holder.createErrorAnnotation(statement, GroovyBundle.message("break.outside.loop.or.switch"));
      }
    }
    if (statement instanceof GrBreakStatement && label != null && findFirstLoop(statement) == null) {
      holder.createErrorAnnotation(statement, GroovyBundle.message("break.outside.loop"));
    }
  }

  @Nullable
  private static GrLoopStatement findFirstLoop(GrFlowInterruptingStatement statement) {
    return PsiTreeUtil.getParentOfType(statement, GrLoopStatement.class, true, GrClosableBlock.class, GrMember.class, GroovyFile.class);
  }

  private static void checkThisOrSuperReferenceExpression(GrExpression expression, AnnotationHolder holder) {
    final GrReferenceExpression qualifier = expression instanceof GrThisReferenceExpression
                                            ? ((GrThisReferenceExpression)expression).getQualifier()
                                            : ((GrSuperReferenceExpression)expression).getQualifier();
    if (qualifier == null) {
      final GrMethod method = PsiTreeUtil.getParentOfType(expression, GrMethod.class);
      if (method != null && method.hasModifierProperty(PsiModifier.STATIC)) {
        holder.createErrorAnnotation(expression, GroovyBundle.message("cannot.reference.nonstatic", expression.getText()));
      }
    }
    else {
      final PsiElement resolved = qualifier.resolve();
      if (resolved instanceof PsiClass) {
        if (PsiTreeUtil.isAncestor(resolved, expression, true)) {
          if (!PsiUtil.hasEnclosingInstanceInScope((PsiClass)resolved, expression, true)) {
            holder.createErrorAnnotation(expression, GroovyBundle.message("cannot.reference.nonstatic", expression.getText()));
          }
        } else {
          holder.createErrorAnnotation(expression, GroovyBundle.message("is.not.enclosing.class", ((PsiClass)resolved).getQualifiedName()));
        }
      }
      else {
        holder.createErrorAnnotation(qualifier, GroovyBundle.message("unknown.class", qualifier.getText()));
      }
    }
  }

  private static void checkStaticDeclarationsInInnerClass(GrMember classMember, AnnotationHolder holder) {
    final PsiClass containingClass = classMember.getContainingClass();
    if (containingClass == null) return;
    if (com.intellij.psi.util.PsiUtil.isInnerClass(containingClass)) {
      if (classMember.hasModifierProperty(PsiModifier.STATIC)) {
        final PsiElement modifier = findModifierStatic(classMember);
        if (modifier != null) {
          holder.createErrorAnnotation(modifier, GroovyBundle.message("cannot.have.static.declarations"));
        }
      }
    }
  }

  @Nullable
  private static PsiElement findModifierStatic(GrMember grMember) {
    final GrModifierList list = grMember.getModifierList();
    if (list == null) {
      return null;
    }

    for (PsiElement modifier : list.getModifiers()) {
      if (PsiModifier.STATIC.equals(modifier.getText())) {
        return modifier;
      }
    }
    return null;
  }

  private static void checkGrDocReferenceElement(AnnotationHolder holder, PsiElement element) {
    ASTNode node = element.getNode();
    if (node != null && TokenSets.BUILT_IN_TYPE.contains(node.getElementType())) {
      Annotation annotation = holder.createInfoAnnotation(element, null);
      annotation.setTextAttributes(DefaultHighlighter.KEYWORD);
    }
  }

  private static void checkAnnotationList(AnnotationHolder holder, @Nullable GrModifierList modifierList, String message) {
    if (modifierList == null) return;
    final PsiElement[] modifiers = modifierList.getModifiers();
    for (PsiElement modifier : modifiers) {
      if (!(modifier instanceof PsiAnnotation)) {
        holder.createErrorAnnotation(modifier, message);
      }
    }
  }

  private static void checkImplementedMethodsOfClass(AnnotationHolder holder, GrTypeDefinition typeDefinition) {
    if (typeDefinition.hasModifierProperty(PsiModifier.ABSTRACT)) return;
    if (typeDefinition.isEnum() || typeDefinition.isAnnotationType()) return;
    if (typeDefinition instanceof GrTypeParameter) return;

    Collection<HierarchicalMethodSignature> allMethods = typeDefinition.getVisibleSignatures();
    PsiMethod abstractMethod = ClassUtil.getAnyAbstractMethod(typeDefinition, allMethods);

    if (abstractMethod == null) return;

    String notImplementedMethodName = abstractMethod.getName();

    final int startOffset = typeDefinition.getTextOffset();
    int endOffset = typeDefinition.getNameIdentifierGroovy().getTextRange().getEndOffset();
    final Annotation annotation = holder.createErrorAnnotation(new TextRange(startOffset, endOffset),
                                                               GroovyBundle.message("method.is.not.implemented", notImplementedMethodName));
    registerImplementsMethodsFix(typeDefinition, annotation);
  }

  private static void registerImplementsMethodsFix(GrTypeDefinition typeDefinition, Annotation annotation) {
    annotation.registerFix(new ImplementMethodsQuickFix(typeDefinition));
  }

  private static void checkInnerMethod(AnnotationHolder holder, GrMethod grMethod) {
    final PsiElement parent = grMethod.getParent();
    if (parent instanceof GrOpenBlock || parent instanceof GrClosableBlock) {
      holder.createErrorAnnotation(grMethod.getNameIdentifierGroovy(), GroovyBundle.message("Inner.methods.are.not.supported"));
    }
  }

  protected static void processDuplicates(Map<GrNamedArgument, List<GrNamedArgument>> map, AnnotationHolder holder) {
    for (List<GrNamedArgument> args : map.values()) {
      for (int i = 1; i < args.size(); i++) {
        GrNamedArgument namedArgument = args.get(i);
        holder.createWarningAnnotation(namedArgument, GroovyBundle.message("duplicate.element.in.the.map"));
      }
    }
  }

  private static void checkMethodDefinitionModifiers(AnnotationHolder holder, GrMethod method) {
    final PsiModifierList modifiersList = method.getModifierList();
    checkAccessModifiers(holder, modifiersList);

    //script methods
    boolean isMethodAbstract = modifiersList.hasExplicitModifier(PsiModifier.ABSTRACT);
    final boolean isMethodStatic = modifiersList.hasExplicitModifier(PsiModifier.STATIC);
    if (method.getParent() instanceof GroovyFileBase) {
      if (isMethodAbstract) {
        holder.createErrorAnnotation(modifiersList, GroovyBundle.message("script.cannot.have.modifier.abstract"));
      }

      if (modifiersList.hasExplicitModifier(PsiModifier.NATIVE)) {
        holder.createErrorAnnotation(modifiersList, GroovyBundle.message("script.cannot.have.modifier.native"));
      }
    }
    else  //type definition methods
      if (method.getParent() != null && method.getParent().getParent() instanceof GrTypeDefinition) {
        GrTypeDefinition containingTypeDef = ((GrTypeDefinition)method.getParent().getParent());

        //interface
        if (containingTypeDef.isInterface()) {
          if (isMethodStatic) {
            holder.createErrorAnnotation(modifiersList, GroovyBundle.message("interface.must.have.no.static.method"));
          }

          if (modifiersList.hasExplicitModifier(PsiModifier.PRIVATE)) {
            holder.createErrorAnnotation(modifiersList, GroovyBundle.message("interface.must.have.no.private.method"));
          }

        }
        else if (containingTypeDef.isEnum()) {
          //enumeration
          //todo
        }
        else if (containingTypeDef.isAnnotationType()) {
          //annotation
          //todo
        }
        else if (containingTypeDef.isAnonymous()) {
          //anonymous class
          if (isMethodStatic) {
            holder.createErrorAnnotation(modifiersList, GroovyBundle.message("static.declaration.in.inner.class"));
          }
          if (method.isConstructor()) {
            holder.createErrorAnnotation(method.getNameIdentifierGroovy(),
                                         GroovyBundle.message("constructors.are.not.allowed.in.anonymous.class"));
          }
          if (isMethodAbstract) {
            holder.createErrorAnnotation(modifiersList, GroovyBundle.message("not.abstract.class.cannot.have.abstract.method"));
          }
        }
        else {
          //class
          PsiModifierList typeDefModifiersList = containingTypeDef.getModifierList();
          LOG.assertTrue(typeDefModifiersList != null, "modifiers list must be not null");

          if (!typeDefModifiersList.hasExplicitModifier(PsiModifier.ABSTRACT)) {
            if (isMethodAbstract) {
              holder.createErrorAnnotation(modifiersList, GroovyBundle.message("not.abstract.class.cannot.have.abstract.method"));
            }
          }

          if (!isMethodAbstract) {
            if (method.getBlock() == null) {
              holder.createErrorAnnotation(method.getNameIdentifierGroovy(), GroovyBundle.message("not.abstract.method.should.have.body"));
            }
          }
          if (isMethodStatic) {
            checkStaticDeclarationsInInnerClass(method, holder);
          }
        }
      }
  }

  private static void checkTypeDefinitionModifiers(AnnotationHolder holder, GrTypeDefinition typeDefinition) {
    PsiModifierList modifiersList = typeDefinition.getModifierList();

    if (modifiersList == null) return;

    /**** class ****/
    checkAccessModifiers(holder, modifiersList);

    PsiClassType[] extendsListTypes = typeDefinition.getExtendsListTypes();

    for (PsiClassType classType : extendsListTypes) {
      PsiClass psiClass = classType.resolve();

      if (psiClass != null) {
        PsiModifierList modifierList = psiClass.getModifierList();
        if (modifierList != null) {
          if (modifierList.hasExplicitModifier(PsiModifier.FINAL)) {
            holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(), GroovyBundle.message("final.class.cannot.be.extended"));
          }
        }
      }
    }

    if (modifiersList.hasExplicitModifier(PsiModifier.ABSTRACT) && modifiersList.hasExplicitModifier(PsiModifier.FINAL)) {
      holder.createErrorAnnotation(modifiersList, GroovyBundle.message("illegal.combination.of.modifiers.abstract.and.final"));
    }

    if (modifiersList.hasExplicitModifier(PsiModifier.TRANSIENT)) {
      holder.createErrorAnnotation(modifiersList, GroovyBundle.message("modifier.transient.not.allowed.here"));
    }
    if (modifiersList.hasExplicitModifier(PsiModifier.VOLATILE)) {
      holder.createErrorAnnotation(modifiersList, GroovyBundle.message("modifier.volatile.not.allowed.here"));
    }

    /**** interface ****/
    if (typeDefinition.isInterface()) {
      if (modifiersList.hasExplicitModifier(PsiModifier.FINAL)) {
        holder.createErrorAnnotation(modifiersList, GroovyBundle.message("intarface.cannot.have.modifier.final"));
      }

      if (modifiersList.hasExplicitModifier(PsiModifier.VOLATILE)) {
        holder.createErrorAnnotation(modifiersList, GroovyBundle.message("modifier.volatile.not.allowed.here"));
      }

      if (modifiersList.hasExplicitModifier(PsiModifier.TRANSIENT)) {
        holder.createErrorAnnotation(modifiersList, GroovyBundle.message("modifier.transient.not.allowed.here"));
      }
    }

    checkStaticDeclarationsInInnerClass(typeDefinition, holder);
  }

  private static void checkAccessModifiers(AnnotationHolder holder, @NotNull PsiModifierList modifierList) {
    boolean hasPrivate = modifierList.hasExplicitModifier(PsiModifier.PRIVATE);
    boolean hasPublic = modifierList.hasExplicitModifier(PsiModifier.PUBLIC);
    boolean hasProtected = modifierList.hasExplicitModifier(PsiModifier.PROTECTED);

    if (hasPrivate && hasPublic || hasPrivate && hasProtected || hasPublic && hasProtected) {
      holder.createErrorAnnotation(modifierList, GroovyBundle.message("illegal.combination.of.modifiers"));
    }
  }

  private static void checkDuplicateMethod(GrMethod[] methods, AnnotationHolder holder) {
    final Map<GrMethod, List<GrMethod>> map = DuplicatesUtil.factorDuplicates(methods, new TObjectHashingStrategy<GrMethod>() {
      public int computeHashCode(GrMethod method) {
        return method.getSignature(PsiSubstitutor.EMPTY).hashCode();
      }

      public boolean equals(GrMethod method1, GrMethod method2) {
        return method1.getSignature(PsiSubstitutor.EMPTY).equals(method2.getSignature(PsiSubstitutor.EMPTY));
      }
    });
    processMethodDuplicates(map, holder);
  }

  protected static void processMethodDuplicates(Map<GrMethod, List<GrMethod>> map, AnnotationHolder holder) {
    HashSet<GrMethod> duplicateMethodsWarning = new HashSet<GrMethod>();
    HashSet<GrMethod> duplicateMethodsErrors = new HashSet<GrMethod>();

    DuplicatesUtil.collectMethodDuplicates(map, duplicateMethodsWarning, duplicateMethodsErrors);

    for (GrMethod duplicateMethod : duplicateMethodsErrors) {
      holder.createErrorAnnotation(duplicateMethod.getNameIdentifierGroovy(),
                                   GroovyBundle.message("repetitive.method.name.signature.and.return.type"));
    }

    for (GrMethod duplicateMethod : duplicateMethodsWarning) {
      holder.createWarningAnnotation(duplicateMethod.getNameIdentifierGroovy(), GroovyBundle.message("repetitive.method.name.signature"));
    }
  }


  private static void checkTypeDefinition(AnnotationHolder holder, GrTypeDefinition typeDefinition) {
    final GroovyConfigUtils configUtils = GroovyConfigUtils.getInstance();
    if (typeDefinition.isAnnotationType()) {
      Annotation annotation = holder.createInfoAnnotation(typeDefinition.getNameIdentifierGroovy(), null);
      annotation.setTextAttributes(DefaultHighlighter.ANNOTATION);
    }
    else if (typeDefinition.isAnonymous()) {
      if (!configUtils.isAtLeastGroovy1_7(typeDefinition)) {
        holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(),
                                     GroovyBundle.message("anonymous.classes.are.not.supported", configUtils.getSDKVersion(typeDefinition)));
      }
    }
    else if (typeDefinition.getContainingClass() != null && !(typeDefinition instanceof GrEnumTypeDefinition)) {
      if (!configUtils.isAtLeastGroovy1_7(typeDefinition)) {
        holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(),
                                     GroovyBundle.message("inner.classes.are.not.supported", configUtils.getSDKVersion(typeDefinition)));
      }
    }

    final GrImplementsClause implementsClause = typeDefinition.getImplementsClause();
    final GrExtendsClause extendsClause = typeDefinition.getExtendsClause();

    if (implementsClause != null) {
      checkForImplementingClass(holder, extendsClause, implementsClause, ((GrTypeDefinition)implementsClause.getParent()));
    }

    if (extendsClause != null) {
      checkForExtendingInterface(holder, extendsClause, implementsClause, ((GrTypeDefinition)extendsClause.getParent()));
    }

    checkDuplicateClass(typeDefinition, holder);
  }

  private static void checkDuplicateClass(GrTypeDefinition typeDefinition, AnnotationHolder holder) {
    final PsiClass containingClass = typeDefinition.getContainingClass();
    if (containingClass != null) {
      final String containingClassName = containingClass.getName();
      if (containingClassName != null && containingClassName.equals(typeDefinition.getName())) {
        holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(),
                                     GroovyBundle.message("duplicate.inner.class", typeDefinition.getName()));
      }
    }
    final String qName = typeDefinition.getQualifiedName();
    if (qName != null) {
      final PsiClass[] classes =
        JavaPsiFacade.getInstance(typeDefinition.getProject()).findClasses(qName, typeDefinition.getResolveScope());
      if (classes.length > 1) {
        String packageName = getPackageName(typeDefinition);

        if (!isScriptGeneratedClass(classes)) {
          holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(),
                                       GroovyBundle.message("duplicate.class", typeDefinition.getName(), packageName));
        }
        else {
          holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(),
                                       GroovyBundle.message("script.generated.with.same.name", qName));
        }
      }
    }
  }

  private static String getPackageName(GrTypeDefinition typeDefinition) {
    final PsiFile file = typeDefinition.getContainingFile();
    String packageName = "<default package>";
    if (file instanceof GroovyFile) {
      final String name = ((GroovyFile)file).getPackageName();
      if (name.length() > 0) packageName = name;
    }
    return packageName;
  }

  private static boolean isScriptGeneratedClass(PsiClass[] allClasses) {
    return allClasses.length == 2 && (allClasses[0] instanceof GroovyScriptClass || allClasses[1] instanceof GroovyScriptClass);
  }

  private static void checkForExtendingInterface(AnnotationHolder holder,
                                          GrExtendsClause extendsClause,
                                          GrImplementsClause implementsClause,
                                          GrTypeDefinition myClass) {
    for (GrCodeReferenceElement ref : extendsClause.getReferenceElements()) {
      final PsiElement clazz = ref.resolve();
      if (clazz == null) continue;

      if (myClass.isInterface() && clazz instanceof PsiClass && !((PsiClass)clazz).isInterface()) {
        final Annotation annotation = holder.createErrorAnnotation(ref, GroovyBundle.message("class.is.not.expected.here"));
        annotation.registerFix(new ChangeExtendsImplementsQuickFix(extendsClause, implementsClause));
      }
    }
  }

  private static void checkForImplementingClass(AnnotationHolder holder,
                                         GrExtendsClause extendsClause,
                                         GrImplementsClause implementsClause,
                                         GrTypeDefinition myClass) {
    if (myClass.isInterface()) {
      final Annotation annotation =
        holder.createErrorAnnotation(implementsClause, GroovyBundle.message("interface.cannot.contain.implements.clause"));
      annotation.registerFix(new ChangeExtendsImplementsQuickFix(extendsClause, implementsClause));
      return;
    }

    for (GrCodeReferenceElement ref : implementsClause.getReferenceElements()) {
      final PsiElement clazz = ref.resolve();
      if (clazz == null) continue;

      if (!((PsiClass)clazz).isInterface()) {
        final Annotation annotation = holder.createErrorAnnotation(ref, GroovyBundle.message("interface.expected.here"));
        annotation.registerFix(new ChangeExtendsImplementsQuickFix(extendsClause, implementsClause));
      }
    }
  }

  private static void checkGrDocMemberReference(final GrDocMemberReference reference, AnnotationHolder holder) {
    PsiElement resolved = reference.resolve();
    if (resolved == null) {
      Annotation annotation = holder.createErrorAnnotation(reference, GroovyBundle.message("cannot.resolve", reference.getReferenceName()));
      annotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
    }
  }

  private static void registerReferenceFixes(GrReferenceExpression refExpr, Annotation annotation) {
    PsiClass targetClass = QuickfixUtil.findTargetClass(refExpr);
    if (targetClass == null) return;

    addDynamicAnnotation(annotation, refExpr);
    if (targetClass instanceof GrMemberOwner) {
      if (!(targetClass instanceof GroovyScriptClass)) {
        annotation.registerFix(new CreateFieldFromUsageFix(refExpr, (GrMemberOwner)targetClass));
      }

      if (refExpr.getParent() instanceof GrCall && refExpr.getParent() instanceof GrExpression) {
        annotation.registerFix(new CreateMethodFromUsageFix(refExpr, (GrMemberOwner)targetClass));
      }
    }

    if (!refExpr.isQualified()) {
      GrVariableDeclarationOwner owner = PsiTreeUtil.getParentOfType(refExpr, GrVariableDeclarationOwner.class);
      if (!(owner instanceof GroovyFileBase) || ((GroovyFileBase)owner).isScript()) {
        annotation.registerFix(new CreateLocalVariableFromUsageFix(refExpr, owner));
      }
    }
  }

  private static void addDynamicAnnotation(Annotation annotation, GrReferenceExpression referenceExpression) {
    final PsiFile containingFile = referenceExpression.getContainingFile();
    VirtualFile file;
    if (containingFile != null) {
      file = containingFile.getVirtualFile();
      if (file == null) return;
    }
    else {
      return;
    }

    annotation
      .registerFix(new DynamicFix(QuickfixUtil.isCall(referenceExpression), referenceExpression), referenceExpression.getTextRange());
  }

  private static void highlightMemberResolved(AnnotationHolder holder, GrReferenceExpression refExpr, PsiMember member) {
    boolean isStatic = member.hasModifierProperty(PsiModifier.STATIC);
    Annotation annotation = holder.createInfoAnnotation(refExpr.getReferenceNameElement(), null);

    if (member instanceof PsiField ) {
      annotation.setTextAttributes(isStatic ? DefaultHighlighter.STATIC_FIELD : DefaultHighlighter.INSTANCE_FIELD);
      return;
    }
    if (member instanceof PsiMethod) {
      annotation.setTextAttributes(!isStatic ? DefaultHighlighter.METHOD_CALL : DefaultHighlighter.STATIC_METHOD_ACCESS);
    }
  }


  private static void registerUsedImport(GrReferenceElement referenceElement, GroovyResolveResult resolveResult) {
    GroovyPsiElement context = resolveResult.getCurrentFileResolveContext();
    if (context instanceof GrImportStatement) {
      PsiFile file = referenceElement.getContainingFile();
      if (file instanceof GroovyFile) {
        GroovyImportsTracker importsTracker = GroovyImportsTracker.getInstance(referenceElement.getProject());
        importsTracker.registerImportUsed((GrImportStatement)context);
      }
    }
  }

  private static void checkMethodApplicability(GroovyResolveResult methodResolveResult, PsiElement place, AnnotationHolder holder) {
    final PsiElement element = methodResolveResult.getElement();
    if (!(element instanceof PsiMethod)) return;
    final PsiMethod method = (PsiMethod)element;
    PsiType[] argumentTypes = PsiUtil.getArgumentTypes(place, method.isConstructor(), true);
    if (argumentTypes != null &&
        !PsiUtil.isApplicable(argumentTypes, method, methodResolveResult.getSubstitutor(),
                              methodResolveResult.getCurrentFileResolveContext() instanceof GrMethodCallExpression)) {
      PsiElement elementToHighlight = PsiUtil.getArgumentsElement(place);
      if (elementToHighlight == null) {
        elementToHighlight = place;
      }

      final String typesString = buildArgTypesList(argumentTypes);
      String message;
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass != null) {
        final PsiClassType containingType = JavaPsiFacade.getInstance(method.getProject()).getElementFactory()
          .createType(containingClass, methodResolveResult.getSubstitutor());
        message = GroovyBundle.message("cannot.apply.method1", method.getName(), containingType.getInternalCanonicalText(), typesString);
      }
      else {
        message = GroovyBundle.message("cannot.apply.method.or.closure", method.getName(), typesString);
      }
      holder.createWarningAnnotation(elementToHighlight, message);
    }
  }

  public static boolean isDeclarationAssignment(GrReferenceExpression refExpr) {
    if (isAssignmentLhs(refExpr)) {
      return isExpandoQualified(refExpr);
    }
    return false;
  }

  private static boolean isAssignmentLhs(GrReferenceExpression refExpr) {
    return refExpr.getParent() instanceof GrAssignmentExpression &&
        refExpr.equals(((GrAssignmentExpression)refExpr.getParent()).getLValue());
  }

  private static boolean isExpandoQualified(GrReferenceExpression refExpr) {
    final GrExpression qualifier = refExpr.getQualifierExpression();
    if (qualifier == null) {
      final PsiClass clazz = PsiTreeUtil.getParentOfType(refExpr, PsiClass.class);
      if (clazz == null) { //script
        return true;
      }
      return false; //in class, a property should normally be defined, so it's not a declaration
    }

    final PsiType type = qualifier.getType();
    if (type instanceof PsiClassType) {
      final PsiClassType classType = (PsiClassType)type;
      final PsiClass psiClass = classType.resolve();
      if (psiClass instanceof GroovyScriptClass) {
        return true;
      }
    }
    return false;
  }

  private static void checkSingleResolvedElement(AnnotationHolder holder, GrReferenceElement refElement, GroovyResolveResult resolveResult) {
    final PsiElement resolved = resolveResult.getElement();
    if (resolved == null) {
      String message = GroovyBundle.message("cannot.resolve", refElement.getReferenceName());

      // Register quickfix
      final PsiElement nameElement = refElement.getReferenceNameElement();
      final PsiElement toHighlight = nameElement != null ? nameElement : refElement;
      final Annotation annotation = holder.createErrorAnnotation(toHighlight, message);
      // todo implement for nested classes
      if (refElement.getQualifier() == null) {
        registerCreateClassByTypeFix(refElement, annotation);
        registerAddImportFixes(refElement, annotation);
      }
      annotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
    }
    else if (!resolveResult.isAccessible()) {
      String message = GroovyBundle.message("cannot.access", refElement.getReferenceName());
      holder.createWarningAnnotation(refElement.getReferenceNameElement(), message);
    }
  }

  private static void checkDefaultMapConstructor(AnnotationHolder holder, GrArgumentList argList) {
    if (argList != null) {
      final GrNamedArgument[] args = argList.getNamedArguments();
      for (GrNamedArgument arg : args) {
        final GrArgumentLabel label = arg.getLabel();
        if (label == null) continue;
        if (label.getName() == null) {
          final PsiElement nameElement = label.getNameElement();
          if (nameElement instanceof GrExpression) {
            final PsiType stringType =
              JavaPsiFacade.getElementFactory(arg.getProject()).createTypeFromText(CommonClassNames.JAVA_LANG_STRING, arg);
            if (!TypesUtil.isAssignable(stringType, ((GrExpression)nameElement).getType(), arg.getManager(), arg.getResolveScope())) {
              holder.createWarningAnnotation(nameElement, GroovyBundle.message("property.name.expected"));
            }
          }
          else {
            holder.createWarningAnnotation(nameElement, GroovyBundle.message("property.name.expected"));
          }
        }
        else {
          final PsiElement resolved = label.resolve();
          if (resolved == null) {
            holder.createWarningAnnotation(label, GroovyBundle.message("no.such.property", label.getName()));
          }
        }
      }
    }
  }

  private static void checkClosureApplicability(GroovyResolveResult resolveResult, PsiType type, PsiElement place, AnnotationHolder holder) {
    final PsiElement element = resolveResult.getElement();
    if (!(element instanceof GrVariable)) return;
    if (!(type instanceof GrClosureType)) return;
    final GrVariable variable = (GrVariable)element;
    PsiType[] argumentTypes = PsiUtil.getArgumentTypes(place, false, true);
    if (argumentTypes == null) return;

    final PsiType[] paramTypes = PsiUtil.skipOptionalClosureParameters(argumentTypes.length, (GrClosureType)type);
    if (!areTypesCompatibleForCallingClosure(argumentTypes, paramTypes, place.getManager(), place.getResolveScope())) {
      final String typesString = buildArgTypesList(argumentTypes);
      String message = GroovyBundle.message("cannot.apply.method.or.closure", variable.getName(), typesString);
      PsiElement elementToHighlight = PsiUtil.getArgumentsElement(place);
      if (elementToHighlight == null) elementToHighlight = place;
      holder.createWarningAnnotation(elementToHighlight, message);
    }
  }

  private static boolean areTypesCompatibleForCallingClosure(PsiType[] argumentTypes,
                                                      PsiType[] paramTypes,
                                                      PsiManager manager,
                                                      GlobalSearchScope resolveScope) {
    if (argumentTypes.length != paramTypes.length) return false;
    for (int i = 0; i < argumentTypes.length; i++) {
      final PsiType paramType = TypesUtil.boxPrimitiveType(paramTypes[i], manager, resolveScope);
      final PsiType argType = argumentTypes[i];
      if (!TypesUtil.isAssignableByMethodCallConversion(paramType, argType, manager, resolveScope)) return false;
    }
    return true;
  }

  private static void registerAddImportFixes(GrReferenceElement refElement, Annotation annotation) {
    final String referenceName = refElement.getReferenceName();
    //noinspection ConstantConditions
    if (StringUtil.isEmpty(referenceName) || Character.isLowerCase(referenceName.charAt(0))) {
      return;
    }

    annotation.registerFix(new GroovyAddImportAction(refElement));
  }

  private static void registerCreateClassByTypeFix(GrReferenceElement refElement, Annotation annotation) {
    GrPackageDefinition packageDefinition = PsiTreeUtil.getParentOfType(refElement, GrPackageDefinition.class);
    if (packageDefinition == null && refElement.getQualifier() == null) {
      PsiElement parent = refElement.getParent();
      if (parent instanceof GrNewExpression) {
        annotation.registerFix(CreateClassFix.createClassFromNewAction((GrNewExpression)parent));
      }
      else {
        annotation.registerFix(CreateClassFix.createClassFixAction(refElement));
      }
    }
  }

  private static void highlightMember(AnnotationHolder holder, GrMember member) {
    if (member instanceof GrField) {
      GrField field = (GrField)member;
      PsiElement identifier = field.getNameIdentifierGroovy();
      final boolean isStatic = field.hasModifierProperty(PsiModifier.STATIC);
      holder.createInfoAnnotation(identifier, null).setTextAttributes(isStatic ? DefaultHighlighter.STATIC_FIELD : DefaultHighlighter.INSTANCE_FIELD);
    }
  }

  private static void highlightAnnotation(AnnotationHolder holder, PsiElement refElement, GroovyResolveResult result) {
    PsiElement element = result.getElement();
    PsiElement parent = refElement.getParent();
    if (element instanceof PsiClass && ((PsiClass)element).isAnnotationType() && !(parent instanceof GrImportStatement)) {
      Annotation annotation = holder.createInfoAnnotation(parent, null);
      annotation.setTextAttributes(DefaultHighlighter.ANNOTATION);
      GroovyPsiElement context = result.getCurrentFileResolveContext();
      if (context instanceof GrImportStatement) {
        annotation = holder.createInfoAnnotation(((GrImportStatement)context).getImportReference(), null);
        annotation.setTextAttributes(DefaultHighlighter.ANNOTATION);
      }
    }

  }


  private static String buildArgTypesList(PsiType[] argTypes) {
    StringBuilder builder = new StringBuilder();
    builder.append("(");
    for (int i = 0; i < argTypes.length; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      PsiType argType = argTypes[i];
      builder.append(argType != null ? argType.getInternalCanonicalText() : "?");
    }
    builder.append(")");
    return builder.toString();
  }

  private static class DuplicateVariablesProcessor extends PropertyResolverProcessor {
    boolean borderPassed;

    public DuplicateVariablesProcessor(GrVariable variable) {
      super(variable.getName(), variable);
      borderPassed = false;
    }

    @Override
    public boolean execute(PsiElement element, ResolveState state) {
      if (borderPassed) {
        return false;
      }
      return super.execute(element, state);
    }

    @Override
    public void handleEvent(Event event, Object associated) {
      if (event == ResolveUtil.DECLARATION_SCOPE_PASSED) {
        borderPassed = true;
      }
      super.handleEvent(event, associated);
    }
  }
}

