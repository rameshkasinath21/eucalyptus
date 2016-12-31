/*************************************************************************
 * (c) Copyright 2017 Hewlett Packard Enterprise Development Company LP
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 ************************************************************************/
package com.eucalyptus.auth.euare

import com.google.common.collect.Lists
import com.google.common.collect.Sets
import org.junit.Ignore
import org.junit.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType

/**
 * Developer test that can be used to generate bindings from message classes for euare.
 */
@Ignore("Developer test")
class EuareBindingGenerator {

  @Test
  void generate() {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider( false )
    scanner.addIncludeFilter( new AssignableTypeFilter( EuareMessage ) );
    Set<String> messageClasses = Sets.newTreeSet( scanner.findCandidateComponents( EuareMessage.package.name )*.beanClassName )
    messageClasses.removeAll( [ 'com.eucalyptus.auth.euare.EuareMessage' ] )
    messageClasses.retainAll( [
      'com.eucalyptus.auth.euare.AttachGroupPolicyResponseType',
      'com.eucalyptus.auth.euare.AttachGroupPolicyType',
      'com.eucalyptus.auth.euare.AttachRolePolicyResponseType',
      'com.eucalyptus.auth.euare.AttachRolePolicyType',
      'com.eucalyptus.auth.euare.AttachUserPolicyResponseType',
      'com.eucalyptus.auth.euare.AttachUserPolicyType',
      'com.eucalyptus.auth.euare.CreatePolicyResponseType',
      'com.eucalyptus.auth.euare.CreatePolicyType',
      'com.eucalyptus.auth.euare.CreatePolicyVersionResponseType',
      'com.eucalyptus.auth.euare.CreatePolicyVersionType',
      'com.eucalyptus.auth.euare.DeletePolicyResponseType',
      'com.eucalyptus.auth.euare.DeletePolicyType',
      'com.eucalyptus.auth.euare.DeletePolicyVersionResponseType',
      'com.eucalyptus.auth.euare.DeletePolicyVersionType',
      'com.eucalyptus.auth.euare.DetachGroupPolicyResponseType',
      'com.eucalyptus.auth.euare.DetachGroupPolicyType',
      'com.eucalyptus.auth.euare.DetachRolePolicyResponseType',
      'com.eucalyptus.auth.euare.DetachRolePolicyType',
      'com.eucalyptus.auth.euare.DetachUserPolicyResponseType',
      'com.eucalyptus.auth.euare.DetachUserPolicyType',
      'com.eucalyptus.auth.euare.GetPolicyResponseType',
      'com.eucalyptus.auth.euare.GetPolicyType',
      'com.eucalyptus.auth.euare.GetPolicyVersionResponseType',
      'com.eucalyptus.auth.euare.GetPolicyVersionType',
      'com.eucalyptus.auth.euare.ListAttachedGroupPoliciesResponseType',
      'com.eucalyptus.auth.euare.ListAttachedGroupPoliciesType',
      'com.eucalyptus.auth.euare.ListAttachedRolePoliciesResponseType',
      'com.eucalyptus.auth.euare.ListAttachedRolePoliciesType',
      'com.eucalyptus.auth.euare.ListAttachedUserPoliciesResponseType',
      'com.eucalyptus.auth.euare.ListAttachedUserPoliciesType',
      'com.eucalyptus.auth.euare.ListEntitiesForPolicyResponseType',
      'com.eucalyptus.auth.euare.ListEntitiesForPolicyType',
      'com.eucalyptus.auth.euare.ListPoliciesResponseType',
      'com.eucalyptus.auth.euare.ListPoliciesType',
      'com.eucalyptus.auth.euare.ListPolicyVersionsResponseType',
      'com.eucalyptus.auth.euare.ListPolicyVersionsType',
      'com.eucalyptus.auth.euare.SetDefaultPolicyVersionResponseType',
      'com.eucalyptus.auth.euare.SetDefaultPolicyVersionType'
     ] )

    Set<String> beanClasses = Sets.newTreeSet( )
    Set<String> printedBeanClasses = Sets.newHashSet( )
    for ( String messageclass : messageClasses ) {
      String simpleName = messageclass.substring( messageclass.lastIndexOf( '.' ) + 1 )

      if ( messageclass.endsWith( "Type" ) ) {
        println """  <mapping name="${simpleName.substring(0,simpleName.length()-4)}" class="${messageclass}" extends="com.eucalyptus.auth.euare.EuareMessage">"""
        println """    <structure map-as="com.eucalyptus.auth.euare.EuareMessage"/>"""
        printFields( messageclass, beanClasses )
        println """  </mapping>"""
      } else {
        throw new Exception( "Unexpected message class : ${messageclass}" )
      }
    }

    while ( !printedBeanClasses.containsAll( beanClasses ) ) {
      for ( String beanClass : Lists.newArrayList( beanClasses ) ) {
        if ( printedBeanClasses.contains( beanClass ) ) continue;
        println """  <mapping class="${beanClass}" abstract="true">"""
        printFields( beanClass, beanClasses )
        println """  </mapping>"""
        printedBeanClasses << beanClass
      }
    }
  }

  void printFields( String messageclass, Collection<String> beanClasses ) {
    Class.forName( messageclass ).declaredFields.each { Field field ->
      if ( field.name.startsWith( '$' ) || field.name.startsWith( '_' ) || field.type.package.name.startsWith( 'groovy.lang' ) ) {
        // ignore
      } else if ( Collection.class.isAssignableFrom( field.type ) ) {
        Class itemType = ( Class ) ( ( ParameterizedType ) field.genericType ).actualTypeArguments[0];
        if ( itemType.name.startsWith( 'java' ) ) {
          println """    <collection name="${field.name.substring(0,1).toUpperCase()}${field.name.substring(1)}" field="${field.name}" usage="optional">"""
          println """      <value name="member" type="${itemType.name}"/>"""
          println """    </collection>"""
        } else {
          println """    <collection name="${field.name.substring(0,1).toUpperCase()}${field.name.substring(1)}" field="${field.name}" usage="optional">>"""
          println """      <structure name="member" type="${itemType.name}"/>"""
          println """    </collection>"""
          beanClasses << itemType.name
        }
      } else if ( field.type.package.name.startsWith( 'java' ) ) {
        println """    <value name="${field.name.substring(0,1).toUpperCase()}${field.name.substring(1)}" field="${field.name}" usage="optional"/>"""
      } else {
        println """    <structure name="${field.name.substring(0,1).toUpperCase()}${field.name.substring(1)}" field="${field.name}" usage="optional" type="${field.type.name}"/>"""
        beanClasses << field.type.name
      }
    }
  }
}
