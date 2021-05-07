/*
 * Copyright 2011 the original author or authors.
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
package org.modelmapper.internal;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.modelmapper.config.Configuration;
import org.modelmapper.config.Configuration.AccessLevel;
import org.modelmapper.internal.util.Types;
import org.modelmapper.spi.NameTransformer;
import org.modelmapper.spi.NameableType;
import org.modelmapper.spi.NamingConvention;
import org.modelmapper.spi.PropertyInfo;
import org.modelmapper.spi.PropertyType;
import org.modelmapper.spi.ValueReader;
import org.modelmapper.spi.ValueWriter;

/**
 * Resolves sets of PropertyInfo for a type's accessors or mutators.
 * 
 * @author Jonathan Halterman
 */
final class PropertyInfoSetResolver {
  private PropertyInfoSetResolver() {
  }

  private static class ResolveRequest<M extends AccessibleObject & Member, PI extends PropertyInfo> {
    PropertyInfoResolver<M, PI> propertyResolver;
    PropertyType propertyType;
    Configuration config;
    AccessLevel accessLevel;
    NamingConvention namingConvention;
    NameTransformer nameTransformer;
  }

  static boolean canAccessMember(Member member, AccessLevel accessLevel) {
    int mod = member.getModifiers();
    switch (accessLevel) {
      default:
      case PUBLIC:
        return Modifier.isPublic(mod);
      case PROTECTED:
        return Modifier.isPublic(mod) || Modifier.isProtected(mod);
      case PACKAGE_PRIVATE:
        return Modifier.isPublic(mod) || Modifier.isProtected(mod) || !Modifier.isPrivate(mod);
      case PRIVATE:
        return true;
    }
  }

  static <T> Map<String, Accessor> resolveAccessors(T source, Class<T> type,
      InheritingConfiguration configuration) {
    ValueReader<T> valueReader = configuration.valueAccessStore.getFirstSupportedReader(type);
    if (source != null && valueReader != null)
      return resolveAccessorsFromValueReader(source, configuration, valueReader);
    else
      return resolveProperties(type, true, configuration);
  }

  static <T> Map<String, Accessor> resolveAccessorsFromValueReader(T source,
      InheritingConfiguration configuration, ValueReader<T> valueReader) {
    Map<String, Accessor> accessors = new LinkedHashMap<String, Accessor>();
    NameTransformer nameTransformer = configuration.getSourceNameTransformer();
    for (String memberName : valueReader.memberNames(source)) {
      ValueReader.Member<?> member = valueReader.getMember(source, memberName);
      if (member != null)
        accessors.put(nameTransformer.transform(memberName, NameableType.GENERIC),
            PropertyInfoImpl.ValueReaderPropertyInfo.fromMember(member, memberName));
    }
    return accessors;
  }

  static <T> Map<String, Mutator> resolveMutators(Class<T> type, InheritingConfiguration configuration) {
    ValueWriter<T> valueWriter = configuration.valueMutateStore.getFirstSupportedWriter(type);
    if (valueWriter != null && valueWriter.isResolveMembersSupport())
      return resolveMutatorsFromValueWriter(type, configuration, valueWriter);
    return resolveProperties(type, false, configuration);
  }
  static <T> Map<String, Mutator> resolveMutatorsFromValueWriter(Class<T> type,
      InheritingConfiguration configuration, ValueWriter<T> valueWriter) {
    Map<String, Mutator> mutators = new LinkedHashMap<String, Mutator>();
    NameTransformer nameTransformer = configuration.getSourceNameTransformer();
    for (String memberName : valueWriter.memberNames(type)) {
      ValueWriter.Member<?> member = valueWriter.getMember(type, memberName);
      if (member != null)
        mutators.put(nameTransformer.transform(memberName, NameableType.GENERIC),
            PropertyInfoImpl.ValueWriterPropertyInfo.fromMember(member, memberName));
    }
    return mutators;
  }

  @SuppressWarnings({ "unchecked" })
  private static <M extends AccessibleObject & Member, PI extends PropertyInfo> Map<String, PI> resolveProperties(
      Class<?> type, boolean access, Configuration configuration) {
    Map<String, PI> properties = new LinkedHashMap<String, PI>();
    if (configuration.isFieldMatchingEnabled()) {
      properties.putAll(resolveProperties(type, type, PropertyInfoSetResolver.<M, PI>resolveRequest(configuration, access, true)));
    }
    properties.putAll(resolveProperties(type, type, PropertyInfoSetResolver.<M, PI>resolveRequest(configuration, access, false)));
    return properties;
  }

  /**
   * Populates the {@code resolveRequest.propertyInfo} with {@code resolveRequest.propertyResolver}
   * resolved property info for properties that are accessible by the
   * {@code resolveRequest.accessLevel} and satisfy the {@code resolveRequest.namingConvention}.
   * Uses a depth-first search so that child properties of the same name override parents.
   */
  private static <M extends AccessibleObject & Member, PI extends PropertyInfo> Map<String, PI> resolveProperties(
      Class<?> initialType, Class<?> type, ResolveRequest<M, PI> resolveRequest) {
    if (!Types.mightContainsProperties(type) || Types.isInternalType(type)) {
      return new LinkedHashMap<String, PI>();
    }
    Map<String, PI> properties = new LinkedHashMap<String, PI>();
    Class<?> superType = type.getSuperclass();
    if (superType != null && superType != Object.class && superType != Enum.class)
      properties.putAll(resolveProperties(initialType, superType, resolveRequest));

    for (M member : resolveRequest.propertyResolver.membersFor(type)) {
      if (canAccessMember(member, resolveRequest.accessLevel)
          && resolveRequest.propertyResolver.isValid(member)
          && resolveRequest.namingConvention.applies(member.getName(), resolveRequest.propertyType)) {
        String name = resolveRequest.nameTransformer.transform(member.getName(),
            PropertyType.FIELD.equals(resolveRequest.propertyType) ? NameableType.FIELD
                : NameableType.METHOD);
        PI info = resolveRequest.propertyResolver.propertyInfoFor(initialType, member,
            resolveRequest.config, name);
        properties.put(name, info);

        if (!Modifier.isPublic(member.getModifiers())
            || !Modifier.isPublic(member.getDeclaringClass().getModifiers()))
          try {
            member.setAccessible(true);
          } catch (SecurityException e) {
            throw new AssertionError(e);
          }
      }
    }

    return properties;
  }

  @SuppressWarnings("unchecked")
  private static <M extends AccessibleObject & Member, PI extends PropertyInfo> ResolveRequest<M, PI> resolveRequest(
      Configuration configuration, boolean access, boolean field) {
    ResolveRequest<M, PI> resolveRequest = new ResolveRequest<M, PI>();
    resolveRequest.config = configuration;
    if (access) {
      resolveRequest.namingConvention = configuration.getSourceNamingConvention();
      resolveRequest.nameTransformer = configuration.getSourceNameTransformer();
    } else {
      resolveRequest.namingConvention = configuration.getDestinationNamingConvention();
      resolveRequest.nameTransformer = configuration.getDestinationNameTransformer();
    }

    if (field) {
      resolveRequest.propertyType = PropertyType.FIELD;
      resolveRequest.accessLevel = configuration.getFieldAccessLevel();
      resolveRequest.propertyResolver = (PropertyInfoResolver<M, PI>) PropertyInfoResolver.FIELDS;
    } else {
      resolveRequest.propertyType = PropertyType.METHOD;
      resolveRequest.accessLevel = configuration.getMethodAccessLevel();
      resolveRequest.propertyResolver = (PropertyInfoResolver<M, PI>) (access ? PropertyInfoResolver.ACCESSORS
          : PropertyInfoResolver.MUTATORS);
    }
    return resolveRequest;
  }
}
