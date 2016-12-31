/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
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
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.auth.euare.persist.entities;

import static com.eucalyptus.upgrade.Upgrades.Version.v4_2_0;
import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceContext;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import org.apache.log4j.Logger;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import com.eucalyptus.auth.util.Identifiers;
import com.eucalyptus.component.id.Euare;
import com.eucalyptus.crypto.Crypto;
import com.eucalyptus.entities.AbstractPersistent;
import com.eucalyptus.upgrade.Upgrades;
import groovy.sql.Sql;

/**
 * Database entity for a role.
 */
@Entity
@PersistenceContext( name = "eucalyptus_auth" )
@Table( name = "auth_role", indexes = {
    @Index( name = "auth_role_name_idx", columnList = "auth_role_name" ),
    @Index( name = "auth_role_owning_account_idx", columnList = "auth_role_owning_account" )
} )
public class RoleEntity extends AbstractPersistent implements Serializable {

  private static final long serialVersionUID = 1L;

  // The Role ID the user facing role id which conforms to length and character restrictions per spec.
  @Column( name = "auth_role_id_external", nullable = false, updatable = false, unique = true )
  private String roleId;

  // Role name
  @Column( name = "auth_role_name", nullable = false)
  private String name;

  // Role path (prefix to organize role name space)
  @Column( name = "auth_role_path", nullable = false )
  private String path;

  @Column( name="auth_role_secret", nullable = false )
  private String secret;

  @OneToOne( cascade = CascadeType.ALL, optional = false, orphanRemoval = true )
  @JoinColumn( name = "auth_role_assume_role_policy_id", nullable = false )
  private PolicyEntity assumeRolePolicy;

  @OneToMany( cascade = CascadeType.ALL, mappedBy = "role" )
  private Set<PolicyEntity> policies;

  // Attached policies for the role
  @NotFound( action = NotFoundAction.IGNORE )
  @JoinTable( name = "auth_role_attached_policies",
      joinColumns =        @JoinColumn( name = "auth_role_id" ),
      inverseJoinColumns = @JoinColumn( name = "auth_managed_policy_id" ) )
  @ManyToMany
  Set<ManagedPolicyEntity> attachedPolicies;

  @OneToMany( mappedBy = "role" )
  private Set<InstanceProfileEntity> instanceProfiles;

  @ManyToOne
  @JoinColumn( name = "auth_role_owning_account", nullable = false )
  private AccountEntity account;

  @Column( name = "auth_role_unique_name", unique = true, nullable = false )
  private String uniqueName;

  public RoleEntity( ) {
  }

  public RoleEntity( final String name ) {
    this( );
    this.name = name;
  }

  public String getRoleId() {
    return roleId;
  }

  public void setRoleId( final String roleId ) {
    this.roleId = roleId;
  }

  public String getName() {
    return name;
  }

  public void setName( final String name ) {
    this.name = name;
  }

  public String getPath() {
    return path;
  }

  public void setPath( final String path ) {
    this.path = path;
  }

  public String getSecret() {
    return secret;
  }

  public void setSecret( final String secret ) {
    this.secret = secret;
  }

  public PolicyEntity getAssumeRolePolicy() {
    return assumeRolePolicy;
  }

  public void setAssumeRolePolicy( final PolicyEntity assumeRolePolicy ) {
    this.assumeRolePolicy = assumeRolePolicy;
  }

  public Set<PolicyEntity> getPolicies() {
    return policies;
  }

  public void setPolicies( final Set<PolicyEntity> policies ) {
    this.policies = policies;
  }

  public Set<ManagedPolicyEntity> getAttachedPolicies( ) {
    return attachedPolicies;
  }

  public void setAttachedPolicies( final Set<ManagedPolicyEntity> attachedPolicies ) {
    this.attachedPolicies = attachedPolicies;
  }

  public Set<InstanceProfileEntity> getInstanceProfiles() {
    return instanceProfiles;
  }

  public void setInstanceProfiles( final Set<InstanceProfileEntity> instanceProfiles ) {
    this.instanceProfiles = instanceProfiles;
  }

  public AccountEntity getAccount() {
    return account;
  }

  public void setAccount( final AccountEntity account ) {
    this.account = account;
  }

  @PrePersist
  @PreUpdate
  public void generateOnCommit() {
    if( this.roleId == null ) {
      this.roleId = Identifiers.generateIdentifier( "ARO" );
    }
    if ( this.secret == null ) {
      this.secret = Crypto.generateSecretKey();
    }
    this.uniqueName = account.getAccountNumber() + ":" + name;
  }

  @Override
  public String toString( ) {
    StringBuilder sb = new StringBuilder( );
    sb.append( "Role(" );
    sb.append( "id=" ).append( this.getId() ).append( ", " );
    sb.append( "roleId=" ).append( this.getRoleId() ).append( ", " );
    sb.append( "name=" ).append( this.getName() ).append( ", " );
    sb.append( "path=" ).append( this.getPath() ).append( ", " );
    sb.append( ")" );
    return sb.toString( );
  }

  @Upgrades.PreUpgrade( value = Euare.class, since = v4_2_0 )
  public static class RolePreUpgrade420 implements Callable<Boolean> {
    private static final Logger logger = Logger.getLogger( RolePreUpgrade420.class );

    @Override
    public Boolean call( ) throws Exception {
      Sql sql = null;
      try {
        sql = Upgrades.DatabaseFilters.NEWVERSION.getConnection("eucalyptus_auth");
        sql.execute( "alter table auth_role add constraint uk_en00jos6jjrjjxooo3mlhg3sn unique ( auth_role_id_external )" );
        return true;
      } catch (Exception ex) {
        logger.error( ex, ex );
        return false;
      } finally {
        if (sql != null) {
          sql.close();
        }
      }
    }
  }  
}
