/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.java;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.builtins.CompanionObjectMapping;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationWithTarget;
import org.jetbrains.kotlin.metadata.ProtoBuf;
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter;
import org.jetbrains.kotlin.resolve.scopes.MemberScope;
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor;
import org.jetbrains.kotlin.util.capitalizeDecapitalize.CapitalizeDecapitalizeKt;

import java.util.Collection;
import java.util.List;

import static org.jetbrains.kotlin.resolve.DescriptorUtils.*;

public final class JvmAbi {
    public static final String DEFAULT_IMPLS_CLASS_NAME = "DefaultImpls";
    public static final String ERASED_INLINE_CLASS_NAME = "Erased";
    public static final FqName JVM_FIELD_ANNOTATION_FQ_NAME = new FqName("kotlin.jvm.JvmField");

    /**
     * Warning: use DEFAULT_IMPLS_CLASS_NAME and TypeMappingConfiguration.innerClassNameFactory when possible.
     * This is false for KAPT3 mode.
     */
    public static final String DEFAULT_IMPLS_SUFFIX = "$" + DEFAULT_IMPLS_CLASS_NAME;
    public static final String DEFAULT_IMPLS_DELEGATE_SUFFIX = "$defaultImpl";

    public static final String DEFAULT_PARAMS_IMPL_SUFFIX = "$default";

    private static final String GET_PREFIX = "get";
    private static final String IS_PREFIX = "is";
    private static final String SET_PREFIX = "set";

    public static final String DELEGATED_PROPERTY_NAME_SUFFIX = "$delegate";
    public static final String DELEGATED_PROPERTIES_ARRAY_NAME = "$$delegatedProperties";
    public static final String DELEGATE_SUPER_FIELD_PREFIX = "$$delegate_";
    private static final String ANNOTATIONS_SUFFIX = "$annotations";
    private static final String ANNOTATED_PROPERTY_METHOD_NAME_SUFFIX = ANNOTATIONS_SUFFIX;
    private static final String ANNOTATED_TYPEALIAS_METHOD_NAME_SUFFIX = ANNOTATIONS_SUFFIX;

    public static final String INSTANCE_FIELD = "INSTANCE";
    public static final String HIDDEN_INSTANCE_FIELD = "$$" + INSTANCE_FIELD;

    public static final String DEFAULT_MODULE_NAME = "main";
    public static final ClassId REFLECTION_FACTORY_IMPL = ClassId.topLevel(new FqName("kotlin.reflect.jvm.internal.ReflectionFactoryImpl"));

    public static final String LOCAL_VARIABLE_NAME_PREFIX_INLINE_ARGUMENT = "$i$a$";
    public static final String LOCAL_VARIABLE_NAME_PREFIX_INLINE_FUNCTION = "$i$f$";

    public static final String ERASED_INLINE_CLASS_SUFFIX = "$" + ERASED_INLINE_CLASS_NAME;

    @NotNull
    public static String getSyntheticMethodNameForAnnotatedProperty(@NotNull Name propertyName) {
        return propertyName.asString() + ANNOTATED_PROPERTY_METHOD_NAME_SUFFIX;
    }

    @NotNull
    public static String getSyntheticMethodNameForAnnotatedTypeAlias(@NotNull Name typeAliasName) {
        return typeAliasName.asString() + ANNOTATED_TYPEALIAS_METHOD_NAME_SUFFIX;
    }

    public static boolean isGetterName(@NotNull String name) {
        return name.startsWith(GET_PREFIX) || name.startsWith(IS_PREFIX);
    }

    public static boolean isSetterName(@NotNull String name) {
        return name.startsWith(SET_PREFIX);
    }

    @NotNull
    public static String getterName(@NotNull String propertyName) {
        return startsWithIsPrefix(propertyName)
               ? propertyName
               : GET_PREFIX + CapitalizeDecapitalizeKt.capitalizeAsciiOnly(propertyName);

    }

    @NotNull
    public static String setterName(@NotNull String propertyName) {
        return SET_PREFIX +
               (startsWithIsPrefix(propertyName)
                ? propertyName.substring(IS_PREFIX.length())
                : CapitalizeDecapitalizeKt.capitalizeAsciiOnly(propertyName));
    }

    public static boolean startsWithIsPrefix(String name) {
        if (!name.startsWith(IS_PREFIX)) return false;
        if (name.length() == IS_PREFIX.length()) return false;
        char c = name.charAt(IS_PREFIX.length());
        return !('a' <= c && c <= 'z');
    }

    public static boolean isPropertyWithBackingFieldInOuterClass(@NotNull PropertyDescriptor propertyDescriptor) {
        return propertyDescriptor.getKind() != CallableMemberDescriptor.Kind.FAKE_OVERRIDE &&
               (isCompanionObjectWithBackingFieldsInOuter(propertyDescriptor.getContainingDeclaration())
                ||
                (isCompanionObject(propertyDescriptor.getContainingDeclaration()) &&
                 hasJvmFieldAnnotation(propertyDescriptor)));
    }

    public static boolean isCompanionObjectWithBackingFieldsInOuter(@NotNull DeclarationDescriptor companionObject) {
        return isCompanionObject(companionObject) &&
               (isClassOrEnumClass(companionObject.getContainingDeclaration()) ||
                isInterfaceCompanionWithBackingFieldsInOuter(companionObject)) &&
               !isMappedIntrinsicCompanionObject((ClassDescriptor) companionObject);

    }

    public static boolean isInterfaceCompanionWithBackingFieldsInOuter(@NotNull DeclarationDescriptor declarationDescriptor) {
        DeclarationDescriptor interfaceClass = declarationDescriptor.getContainingDeclaration();
        if (!isCompanionObject(declarationDescriptor) ||
            (!isInterface(interfaceClass) && !isAnnotationClass(interfaceClass))) {
            return false;
        }
        Collection<DeclarationDescriptor> descriptors = ((ClassDescriptor) declarationDescriptor).getUnsubstitutedMemberScope()
                .getContributedDescriptors(DescriptorKindFilter.ALL, MemberScope.Companion.getALL_NAME_FILTER());
        boolean hasJvmFieldAnnotation = false;
        for (DeclarationDescriptor next : descriptors) {
            if (!(next instanceof PropertyDescriptor)) continue;
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) next;

            if (propertyDescriptor.getVisibility() != Visibilities.PUBLIC ||
                propertyDescriptor.isVar() ||
                propertyDescriptor.getModality() != Modality.FINAL) {
                return false;
            }

            if (!isMovedFromInterfaceCompanion(propertyDescriptor) && !hasJvmFieldAnnotation(propertyDescriptor)) return false;
            hasJvmFieldAnnotation = true;
        }
        return hasJvmFieldAnnotation;
    }

    private static boolean isMovedFromInterfaceCompanion(@NotNull PropertyDescriptor propertyDescriptor) {
        if (propertyDescriptor instanceof DeserializedPropertyDescriptor) {
            ProtoBuf.Property proto = ((DeserializedPropertyDescriptor) propertyDescriptor).getProto();
            return JvmProtoBufUtil.isMovedFromInterfaceCompanion(proto);
        }
        return false;
    }

    public static boolean isMappedIntrinsicCompanionObject(@NotNull ClassDescriptor companionObject) {
        return CompanionObjectMapping.INSTANCE.isMappedIntrinsicCompanionObject(companionObject);
    }

    private static boolean hasJvmFieldAnnotation(@NotNull CallableMemberDescriptor memberDescriptor) {
        List<AnnotationWithTarget> annotations = memberDescriptor.getAnnotations().getUseSiteTargetedAnnotations();
        for (AnnotationWithTarget annotationWithTarget : annotations) {
            if (AnnotationUseSiteTarget.FIELD.equals(annotationWithTarget.getTarget()) &&
                JVM_FIELD_ANNOTATION_FQ_NAME.equals(annotationWithTarget.getAnnotation().getFqName())) {
                return true;
            }
        }
        return memberDescriptor.getAnnotations().hasAnnotation(JVM_FIELD_ANNOTATION_FQ_NAME);
    }
}
