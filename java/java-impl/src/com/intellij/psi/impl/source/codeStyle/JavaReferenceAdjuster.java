/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.psi.impl.source.codeStyle;

import com.intellij.lang.ASTNode;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.ReferenceAdjuster;
import com.intellij.psi.impl.PsiImplUtil;
import com.intellij.psi.impl.source.PsiJavaCodeReferenceElementImpl;
import com.intellij.psi.impl.source.SourceJavaCodeReference;
import com.intellij.psi.impl.source.jsp.jspJava.JspClass;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.jsp.JspFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class JavaReferenceAdjuster implements ReferenceAdjuster {
  private final boolean myUseFqClassNamesInJavadoc;
  private final boolean myUseFqClassNames;

  public JavaReferenceAdjuster(boolean useFqInJavadoc, boolean useFqInCode) {
    myUseFqClassNamesInJavadoc = useFqInJavadoc;
    myUseFqClassNames = useFqInCode;
  }

  public JavaReferenceAdjuster(CodeStyleSettings settings) {
    this(settings.USE_FQ_CLASS_NAMES_IN_JAVADOC, settings.USE_FQ_CLASS_NAMES);
  }

  @Override
  public ASTNode process(ASTNode element, boolean addImports, boolean incompleteCode) {
    IElementType elementType = element.getElementType();
    if ((elementType == JavaElementType.JAVA_CODE_REFERENCE || elementType == JavaElementType.REFERENCE_EXPRESSION) && !isAnnotated(element)) {
      IElementType parentType = element.getTreeParent().getElementType();
      if (elementType == JavaElementType.JAVA_CODE_REFERENCE || incompleteCode ||
          parentType == JavaElementType.REFERENCE_EXPRESSION || parentType == JavaElementType.METHOD_REF_EXPRESSION) {
        PsiJavaCodeReferenceElement ref = (PsiJavaCodeReferenceElement)element.getPsi();

        PsiReferenceParameterList parameterList = ref.getParameterList();
        if (parameterList != null) {
          PsiTypeElement[] typeParameters = parameterList.getTypeParameterElements();
          for (PsiTypeElement typeParameter : typeParameters) {
            process(typeParameter.getNode(), addImports, incompleteCode);
          }
        }

        boolean rightKind = true;
        if (elementType == JavaElementType.JAVA_CODE_REFERENCE) {
          int kind = ((PsiJavaCodeReferenceElementImpl)element).getKind();
          rightKind = kind == PsiJavaCodeReferenceElementImpl.CLASS_NAME_KIND || kind == PsiJavaCodeReferenceElementImpl.CLASS_OR_PACKAGE_NAME_KIND;
        }

        if (rightKind) {
          boolean isInsideDocComment = TreeUtil.findParent(element, JavaDocElementType.DOC_COMMENT) != null;
          boolean isShort = !ref.isQualified();
          if (!makeFQ(isInsideDocComment)) {
            if (isShort) return element; // short name already, no need to change
          }

          PsiElement refElement;
          if (!incompleteCode) {
            refElement = ref.resolve();
          }
          else {
            PsiResolveHelper helper = JavaPsiFacade.getInstance(ref.getManager().getProject()).getResolveHelper();
            final SourceJavaCodeReference reference = (SourceJavaCodeReference)element;
            refElement = helper.resolveReferencedClass(reference.getClassNameText(), ref);
          }

          if (refElement instanceof PsiClass) {
            PsiClass psiClass = (PsiClass)refElement;
            if (makeFQ(isInsideDocComment)) {
              String qName = psiClass.getQualifiedName();
              if (qName == null) return element;

              PsiFile file = ref.getContainingFile();
              if (file instanceof PsiJavaFile) {
                if (ImportHelper.isImplicitlyImported(qName, (PsiJavaFile)file)) {
                  if (isShort) return element;
                  return makeShortReference((CompositeElement)element, psiClass, addImports);
                }

                String thisPackageName = ((PsiJavaFile)file).getPackageName();
                if (ImportHelper.hasPackage(qName, thisPackageName)) {
                  if (!isShort) {
                    return makeShortReference((CompositeElement)element, psiClass, addImports);
                  }
                }
              }

              return replaceReferenceWithFQ(element, psiClass);
            }
            else {
              int oldLength = element.getTextLength();
              ASTNode treeElement = makeShortReference((CompositeElement)element, psiClass, addImports);
              if (treeElement.getTextLength() == oldLength && psiClass.getContainingClass() != null) {
                PsiElement qualifier = ref.getQualifier();
                if (qualifier instanceof PsiJavaCodeReferenceElement && ((PsiJavaCodeReferenceElement)qualifier).resolve() instanceof PsiClass) {
                  process(qualifier.getNode(), addImports, incompleteCode);
                }
              }
              return treeElement;
            }
          }
        }
      }
    }

    for (ASTNode child = element.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      //noinspection AssignmentToForLoopParameter
      child = process(child, addImports, incompleteCode);
    }

    return element;
  }

  private static boolean isAnnotated(ASTNode element) {
    PsiJavaCodeReferenceElement ref = (PsiJavaCodeReferenceElement)element.getPsi();

    PsiElement qualifier = ref.getQualifier();
    if (qualifier instanceof PsiJavaCodeReferenceElement && PsiTreeUtil.getChildOfType(qualifier, PsiAnnotation.class) != null) {
      return true;
    }

    PsiModifierList modifierList = PsiImplUtil.findNeighbourModifierList(ref);
    if (modifierList != null) {
      for (PsiAnnotation annotation : modifierList.getAnnotations()) {
        if (PsiImplUtil.findApplicableTarget(annotation, PsiAnnotation.TargetType.TYPE_USE) != null) {
          return true;
        }
      }
    }

    return false;
  }

  private boolean makeFQ(boolean isInsideDocComment) {
    return isInsideDocComment ? myUseFqClassNamesInJavadoc : myUseFqClassNames;
  }

  @Override
  public void processRange(ASTNode element, int startOffset, int endOffset) {
    List<ASTNode> array = new ArrayList<ASTNode>();
    addReferencesInRange(array, element, startOffset, endOffset);
    for (ASTNode ref : array) {
      if (ref.getPsi().isValid()) {
        process(ref, true, true);
      }
    }
  }

  private static void addReferencesInRange(List<ASTNode> array, ASTNode parent, int startOffset, int endOffset) {
    if (parent.getElementType() == JavaElementType.JAVA_CODE_REFERENCE || parent.getElementType() == JavaElementType.REFERENCE_EXPRESSION) {
      array.add(parent);
      return;
    }

    if (parent.getPsi() instanceof PsiFile) {
      JspFile jspFile = JspPsiUtil.getJspFile(parent.getPsi());
      if (jspFile != null) {
        JspClass jspClass = (JspClass)jspFile.getJavaClass();
        addReferencesInRange(array, jspClass.getNode(), startOffset, endOffset);
        return;
      }
    }

    addReferencesInRangeForComposite(array, parent, startOffset, endOffset);
  }

  private static void addReferencesInRangeForComposite(List<ASTNode> array, ASTNode parent, int startOffset, int endOffset) {
    int offset = 0;
    for (ASTNode child = parent.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      int length = child.getTextLength();
      if (startOffset <= offset + length && offset <= endOffset) {
        IElementType type = child.getElementType();
        if (type == JavaElementType.JAVA_CODE_REFERENCE || type == JavaElementType.REFERENCE_EXPRESSION) {
          array.add(child);
        }
        else {
          addReferencesInRangeForComposite(array, child, startOffset - offset, endOffset - offset);
        }
      }
      offset += length;
    }
  }

  private static ASTNode makeShortReference(@NotNull CompositeElement reference, @NotNull PsiClass refClass, boolean addImports) {
    @NotNull final PsiJavaCodeReferenceElement psiReference = (PsiJavaCodeReferenceElement)reference.getPsi();
    final PsiQualifiedReferenceElement reference1 = getClassReferenceToShorten(refClass, addImports, psiReference);
    if (reference1 != null) replaceReferenceWithShort(reference1);
    return reference;
  }

  @Nullable
  public static PsiQualifiedReferenceElement getClassReferenceToShorten(@NotNull final PsiClass refClass,
                                                                        final boolean addImports,
                                                                        @NotNull final PsiQualifiedReferenceElement reference) {
    PsiClass parentClass = refClass.getContainingClass();
    if (parentClass != null) {
      JavaPsiFacade facade = JavaPsiFacade.getInstance(parentClass.getProject());
      final PsiResolveHelper resolveHelper = facade.getResolveHelper();
      if (resolveHelper.isAccessible(refClass, reference, null) && isSafeToShortenReference(reference.getReferenceName(), reference, refClass)) {
        return reference;
      }

      if (!CodeStyleSettingsManager.getSettings(reference.getProject()).INSERT_INNER_CLASS_IMPORTS) {
        final PsiElement qualifier = reference.getQualifier();
        if (qualifier instanceof PsiQualifiedReferenceElement) {
          return getClassReferenceToShorten(parentClass, addImports, (PsiQualifiedReferenceElement)qualifier);
        }
        return null;
      }
    }

    if (addImports && !((PsiImportHolder)reference.getContainingFile()).importClass(refClass)) return null;
    if (!isSafeToShortenReference(reference, refClass)) return null;
    return reference;
  }

  private static boolean isSafeToShortenReference(@NotNull PsiElement psiReference, @NotNull PsiClass refClass) {
    return isSafeToShortenReference(refClass.getName(), psiReference, refClass);
  }

  private static boolean isSafeToShortenReference(final String referenceText, final PsiElement psiReference, final PsiClass refClass) {
    final PsiManager manager = refClass.getManager();
    final JavaPsiFacade facade = JavaPsiFacade.getInstance(manager.getProject());
    final PsiResolveHelper helper = facade.getResolveHelper();
    if (manager.areElementsEquivalent(refClass, helper.resolveReferencedClass(referenceText, psiReference))) {
      PsiElement parent = psiReference.getParent();
      if (parent instanceof PsiJavaCodeReferenceElement && parent.getParent() instanceof PsiNewExpression) return true;
      return helper.resolveReferencedVariable(referenceText, psiReference) == null;
    }
    return false;
  }

  @NotNull
  private static ASTNode replaceReferenceWithShort(PsiQualifiedReferenceElement reference) {
    ASTNode node = reference.getNode();
    assert node != null;
    deQualifyImpl((CompositeElement)node);
    return node;
  }

  private static void deQualifyImpl(@NotNull CompositeElement reference) {
    ASTNode qualifier = reference.findChildByRole(ChildRole.QUALIFIER);
    if (qualifier != null) {
      ASTNode firstChildNode = qualifier.getFirstChildNode();
      boolean markToReformatBefore = firstChildNode instanceof TreeElement && CodeEditUtil.isMarkedToReformatBefore((TreeElement)firstChildNode);
      reference.deleteChildInternal(qualifier);
      if (markToReformatBefore) {
        firstChildNode = reference.getFirstChildNode();
        if (firstChildNode != null) {
          CodeEditUtil.markToReformatBefore(firstChildNode, true);
        }
      }
    }
  }

  private static ASTNode replaceReferenceWithFQ(ASTNode reference, PsiClass refClass) {
    ((SourceJavaCodeReference)reference).fullyQualify(refClass);
    return reference;
  }
}
