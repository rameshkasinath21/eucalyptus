/*******************************************************************************
 *Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */
package com.eucalyptus.bootstrap;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;
import com.eucalyptus.component.Component;
import com.eucalyptus.component.Component.State;
import com.eucalyptus.component.Component.Transition;
import com.eucalyptus.component.ComponentId;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.ServiceRegistrationException;
import com.eucalyptus.empyrean.Empyrean;
import com.eucalyptus.records.EventRecord;
import com.eucalyptus.records.EventType;
import com.eucalyptus.scripting.ScriptExecutionFailedException;
import com.eucalyptus.scripting.groovy.GroovyUtil;
import com.eucalyptus.util.LogUtil;
import com.eucalyptus.util.fsm.ExistingTransitionException;
import com.eucalyptus.util.fsm.StateMachine;
import com.eucalyptus.ws.EmpyreanService;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

/**
 * Mechanism for setting up and progressing through the sequence of stages the system goes through
 * during bootstrap. The bootstrap process consists
 * of two phases:
 * 
 * <ol>
 * <li><b>load()</b>: {@link SystemBootstrapper#load()}</li>
 * <li><b>start()</b> {@link SystemBootstrapper#start()}</li>
 * </ol>
 * 
 * Each phase consists of iterating through each {@link Bootstrap.Stage} and executing the
 * associated bootstrappers accordingly.
 * Implementors of {@link Bootstrapper} must declare which {@link Bootstrap.Stage} they are to be
 * executed by specifying the {@link RunDuring} annotation.
 * 
 * NOTE: It is worth noting that the {@link #start()}-phase is <b>NOT</b> executed for the {@link EmpyreanService.Stage.PrivilegedConfiguration} stage. Since
 * privileges must be dropped
 * after {@link EmpyreanService.Stage.PrivilegedConfiguration}.{@link #load()} the bootstrappers
 * would no
 * longer have the indicated privileges.
 * 
 * After a call to {@link #transition()} the current stage can be obtained from {@link #getCurrentStage()}.
 * 
 * Once {@link EmpyreanService.Stage.Final} is reached for {@link SystemBootstrapper#load()} the {@link #getCurrentStage()} is reset to be
 * {@link EmpyreanService.Stage.SystemInit} and {@link SystemBootstrapper#start()} proceeds. Upon completing {@link SystemBootstrapper#start()} the state
 * forever remains {@link EmpyreanService.Stage.Final}.
 * return {@link EmpyreanService.Stage.Final}.
 * 
 * @see Bootstrap.Stage
 * @see PrivilegedConfiguration#start()
 * @see SystemBootstrapper#init()
 * @see SystemBootstrapper#load()
 * @see SystemBootstrapper#start()
 */
public class Bootstrap {
  static Logger LOG = Logger.getLogger( Bootstrap.class );
  
  /**
   * Mechanism for setting up and progressing through the sequence of stages the system goes through
   * during bootstrap. The bootstrap process consists
   * of two phases:
   * <ol>
   * <li><b>load()</b>: {@link SystemBootstrapper#load()}</li>
   * <li><b>start()</b> {@link SystemBootstrapper#start()}</li>
   * </ol>
   * Each phase consists of iterating through each {@link Bootstrap.Stage} and executing the
   * associated bootstrappers accordingly.
   * 
   * NOTE: It is worth noting that the {@link #start()}-phase is <b>NOT</b> executed for the {@link EmpyreanService.Stage.PrivilegedConfiguration} stage. Since
   * privileges must be dropped
   * after {@link EmpyreanService.Stage.PrivilegedConfiguration}.{@link #load()} the bootstrappers
   * would no
   * longer have the indicated privileges.
   * 
   * Once {@link EmpyreanService.Stage.Final} is reached for {@link SystemBootstrapper#load()} the {@link #getCurrentStage()} is reset to be
   * {@link EmpyreanService.Stage.SystemInit} and {@link SystemBootstrapper#start()} proceeds. Upon completing {@link SystemBootstrapper#start()} the state
   * forever remains {@link EmpyreanService.Stage.Final}.
   * return {@link EmpyreanService.Stage.Final}.
   * 
   * @see PrivilegedConfiguration#start()
   * @see SystemBootstrapper#init()
   * @see SystemBootstrapper#load()
   * @see SystemBootstrapper#start()
   */
  public enum Stage {
    SystemInit {
      /**
       * Nothing is allowed to execute during the start phase of this {@link Bootstrap.Stage}
       * 
       * @see com.eucalyptus.bootstrap.Bootstrap.Stage#start()
       */
      @Override
      public void start( ) {
        for ( Bootstrapper b : this.getBootstrappers( ) ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, this.name( ), "SKIPPING start()", b.getClass( ).getCanonicalName( ) ).warn( );
        }
      }
    },
    PrivilegedConfiguration {
      /**
       * Nothing is allowed to execute during the start phase of this {@link Bootstrap.Stage}
       * 
       * @see com.eucalyptus.bootstrap.Bootstrap.Stage#start()
       */
      @Override
      public void start( ) {
        for ( Bootstrapper b : this.getBootstrappers( ) ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, this.name( ), "SKIPPING start()", b.getClass( ).getCanonicalName( ) ).warn( );
        }
      }
    },
    UnprivilegedConfiguration,
    SystemCredentialsInit, /* <-- this means system credentials, not user. */
    RemoteConfiguration,
    DatabaseInit,
    PoolInit,
    PersistenceInit,
    RemoteServicesInit,
    UserCredentialsInit,
    CloudServiceInit,
    Final;
    public static List<Stage> list( ) {
      return Arrays.asList( Stage.values( ) );
    }
    
    private List<Bootstrapper> bootstrappers     = Lists.newArrayList( );
    private List<Bootstrapper> skipBootstrappers = Lists.newArrayList( );
    
    public List<Bootstrapper> getBootstrappers( ) {
      return this.bootstrappers;
    }
    
    void addBootstrapper( Bootstrapper b ) {
      if ( this.bootstrappers.contains( b ) ) {
        throw BootstrapException.throwFatal( "Duplicate bootstrapper registration: " + b.getClass( ).toString( ) );
      } else {
        this.bootstrappers.add( b );
      }
    }
    
    void skipBootstrapper( Bootstrapper b ) {
      if ( this.skipBootstrappers.contains( b ) ) {
        throw BootstrapException.throwFatal( "Duplicate bootstrapper registration: " + b.getClass( ).toString( ) );
      } else {
        this.skipBootstrappers.add( b );
      }
    }
    
    private void printAgenda( ) {
      if ( !this.bootstrappers.isEmpty( ) ) {
        LOG.info( LogUtil.header( "Bootstrap stage: " + this.name( ) + "." + ( Bootstrap.loading
          ? "load()"
          : "start()" ) ) );
        LOG.debug( Joiner.on( " " ).join( this.name( ) + " bootstrappers:  ", this.bootstrappers ) );
      }
    }
    
    public void updateBootstrapDependencies( ) {
      for ( Bootstrapper b : Lists.newArrayList( this.bootstrappers ) ) {
        if ( !b.checkLocal( ) ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, "stage:" + Bootstrap.getCurrentStage( ), this.getClass( ).getSimpleName( ),
                            "Depends.local=" + b.toString( ), "Component." + b.toString( ) + "=remote" ).info( );
          this.bootstrappers.remove( b );
        } else if ( !b.checkRemote( ) ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, "stage:" + Bootstrap.getCurrentStage( ), this.getClass( ).getSimpleName( ),
                            "Depends.remote=" + b.toString( ), "Component." + b.toString( ) + "=local" ).info( );
          this.bootstrappers.remove( b );
        }
      }
    }
    
    public void load( ) {
      this.updateBootstrapDependencies( );
      this.printAgenda( );
      for ( Bootstrapper b : this.bootstrappers ) {
        try {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_LOAD, this.name( ), b.getClass( ).getCanonicalName( ) ).info( );
          boolean result = b.load( );
          if ( !result ) {
            throw BootstrapException.throwFatal( b.getClass( ).getSimpleName( ) + " returned 'false' from load( ): terminating bootstrap." );
          }
        } catch ( Throwable e ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_ERROR, this.name( ), b.getClass( ).getCanonicalName( ) ).info( );
          throw BootstrapException.throwFatal( b.getClass( ).getSimpleName( ) + " threw an error in load( ): " + e.getMessage( ), e );
        }
      }
    }
    
    public void start( ) {
      this.updateBootstrapDependencies( );
      this.printAgenda( );
      for ( Bootstrapper b : this.bootstrappers ) {
        try {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_START, this.name( ), b.getClass( ).getCanonicalName( ) ).info( );
          boolean result = b.start( );
          if ( !result ) {
            throw BootstrapException.throwFatal( b.getClass( ).getSimpleName( ) + " returned 'false' from start( ): terminating bootstrap." );
          }
        } catch ( Throwable e ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_ERROR, this.name( ), b.getClass( ).getCanonicalName( ) ).info( );
          throw BootstrapException.throwFatal( b.getClass( ).getSimpleName( ) + " threw an error in start( ): " + e.getMessage( ), e );
        }
      }
    }
    
    public String describe( ) {
      StringBuffer buf = new StringBuffer( this.name( ) ).append( " " );
      for ( Bootstrapper b : this.bootstrappers ) {
        buf.append( b.getClass( ).getSimpleName( ) ).append( " " );
      }
      return buf.append( "\n" ).toString( );
    }
    
  }
  
  private static Boolean loading      = false;
  private static Boolean starting     = false;
  private static Boolean finished     = false;
  private static Stage   currentStage = Stage.SystemInit;
  private static Boolean shutdown     = false;
  
  /**
   * @return Bootstrap.currentStage
   */
  public static Stage getCurrentStage( ) {
    return currentStage;
  }
  
  /**
   * Find and run all discovery implementations (see {@link ServiceJarDiscovery}).
   * 
   * First, find all instantiable descendants of {@link ServiceJarDiscovery}.
   * Second, execute each discovery implementation.
   * <b>NOTE:</b> This method finds the available bootstrappers but does not evaluate their
   * dependency constraints.
   */
  private static void doDiscovery( ) {
    ServiceJarDiscovery.processLibraries( );
    ServiceJarDiscovery.runDiscovery( );
  }
  
  /**
   * TODO: DOCUMENT Bootstrap.java
   */
  @SuppressWarnings( "deprecation" )
  public static void initBootstrappers( ) {
    for ( Bootstrapper bootstrap : BootstrapperDiscovery.getBootstrappers( ) ) {//these have all been checked at discovery time
      try {
        Class<ComponentId> compType;
        String bc = bootstrap.getClass( ).getCanonicalName( );
        Bootstrap.Stage stage = bootstrap.getBootstrapStage( );
        compType = bootstrap.getProvides( );
        EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_INIT, stage.name( ), bc, "component=" + compType.getSimpleName( ) ).info( );
        if ( ComponentId.class.isAssignableFrom( compType ) && !Empyrean.class.equals( compType ) && !ComponentId.class.equals( compType ) ) {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_ADDED, stage.name( ), bc, "component=" + compType.getSimpleName( ) ).info( );
          Components.lookup( compType ).getBootstrapper( ).addBootstrapper( bootstrap );
        } else if ( Bootstrap.checkDepends( bootstrap ) ) {
          if ( Empyrean.class.equals( compType ) ) {
            EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_ADDED, stage.name( ), bc, "component=" + compType.getSimpleName( ) ).info( );
            stage.addBootstrapper( bootstrap );
          } else if ( ComponentId.class.equals( compType ) ) {
            for ( Component c : Components.list( ) ) {
              EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_ADDED, stage.name( ), bc, "component=" + c.getName( ) ).info( );
              c.getBootstrapper( ).addBootstrapper( bootstrap );
            }
          }
        } else {
          EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, stage.name( ), bc, "component=" + compType.getSimpleName( ),
                            "localDepends=" + bootstrap.checkLocal( ), "remoteDepends=" + bootstrap.checkRemote( ) ).info( );
        }
      } catch ( Throwable ex ) {
        LOG.error( ex, ex );
      }
    }
  }
  
  private static boolean checkDepends( Bootstrapper bootstrap ) {
    String bc = bootstrap.getClass( ).getCanonicalName( );
    if ( bootstrap.checkLocal( ) && bootstrap.checkRemote( ) ) {
      return true;
    } else {
      if ( !bootstrap.checkLocal( ) ) {
        EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, currentStage.name( ), bc, "DependsLocal",
                          bootstrap.getDependsLocal( ).toString( ) ).info( );
      } else if ( !bootstrap.checkRemote( ) ) {
        EventRecord.here( Bootstrap.class, EventType.BOOTSTRAPPER_SKIPPED, currentStage.name( ), bc, "DependsRemote",
                          bootstrap.getDependsRemote( ).toString( ) ).info( );
      }
      return false;
    }
  }
  
  /**
   * Subsequent calls to {@link #transition()} trigger the transition through the two-phase
   * (load/start) iteration through the {@link Bootstrap.Stage}s.
   * 
   * After a call to {@link #transition()} the current stage can be obtained from {@link #getCurrentStage()}.
   * 
   * Once {@link EmpyreanService.Stage.Final} is reached for {@link SystemBootstrapper#load()} the {@link #getCurrentStage()} is reset to be
   * {@link EmpyreanService.Stage.SystemInit} and {@link SystemBootstrapper#start()} proceeds. Upon completing {@link SystemBootstrapper#start()} the state
   * forever remains {@link EmpyreanService.Stage.Final}.
   * return {@link EmpyreanService.Stage.Final}.
   * 
   * @return currentStage either the same as before, or the next {@link Bootstrap.Stage}.
   */
  public static synchronized Stage transition( ) {
    if ( currentStage == Stage.SystemInit && !loading && !starting && !finished ) {
      loading = true;
      starting = false;
      finished = false;
    } else if ( currentStage != null ) {
      LOG.info( LogUtil.header( "Bootstrap stage completed: " + currentStage.toString( ) ) );
      if ( Stage.Final.equals( currentStage ) ) {
        currentStage = null;
        if ( loading && !starting && !finished ) {
          loading = true;
          starting = true;
          finished = false;
        } else if ( loading && starting && !finished ) {
          loading = true;
          starting = true;
          finished = true;
        }
        return currentStage;
      }
    }
    int currOrdinal = currentStage != null
      ? currentStage.ordinal( )
      : -1;
    for ( int i = currOrdinal + 1; i <= Stage.Final.ordinal( ); i++ ) {
      currentStage = Stage.values( )[i];
      if ( currentStage.bootstrappers.isEmpty( ) ) {
        LOG.trace( LogUtil.subheader( "Bootstrap stage skipped: " + currentStage.toString( ) ) );
        continue;
      } else {
        return currentStage;
      }
    }
    return currentStage;
  }
  
  public static Boolean isFinished( ) {
    return finished;
  }
  
  public static Boolean isShuttingDown( ) {
    return shutdown;
  }
  
  /**
   * Prepares the system to execute the bootstrap sequence defined by {@link Bootstrap.Stage}.
   * 
   * The initialization phase needs to identify all {@link Bootstrapper} implementations available
   * locally -- this determines what components it is possible to 'bootstrap' on the current host.
   * Subsequently, component configuration is prepared and bootstrapper dependency contraints are
   * evaluated. The bootstrappers which conform to the state of the local system are associated with
   * their respective {@link EmpyreanService.State}.
   * 
   * The following steps are performed in order.
   * 
   * <ol>
   * <li><b>Component configurations</b>: Load the component configuration files from the local jar files. This determines which services it is possible to
   * start in the <tt>local</tt> context.</li>
   * 
   * <li><b>Print configurations</b>: The configuration is printed for review.</li>
   * 
   * <li><b>Discovery ({@link ServiceJarDiscovery}</b>: First, find all instantiable descendants of {@code ServiceJarDiscovery}. Second, execute each discovery
   * implementation. <b>NOTE:</b> This step finds the available bootstrappers but does not evaluate their dependency constraints.</li>
   * 
   * <li><b>Print configurations</b>: The configuration is printed for review.</li>
   * <li><b>Print configurations</b>: The configuration is printed for review.</li>
   * </ol>
   * 
   * @see Component#initService()
   * @see Component#startService(com.eucalyptus.component.ServiceConfiguration)
   * @see ServiceJarDiscovery
   * @see Bootstrap#loadConfigs
   * @see Bootstrap#doDiscovery()
   * 
   * @throws Throwable
   */
  public static void init( ) throws Throwable {
    
    Runtime.getRuntime( ).addShutdownHook( new Thread( ) {
      
      @Override
      public void run( ) {
        Bootstrap.shutdown = Boolean.TRUE;
      }
      
    } );
    /**
     * run discovery to find (primarily) bootstrappers, msg typs, bindings, util-providers, etc. See
     * the descendants of {@link ServiceJarDiscovery}.
     * 
     * @see ServiceJarDiscovery
     */
    LOG.info( LogUtil.header( "Initializing discoverable bootstrap resources." ) );
    Bootstrap.doDiscovery( );
    
    LOG.info( LogUtil.header( "Initializing component identifiers:" ) );
    for ( ComponentId compId : ComponentIds.list( ) ) {
      LOG.info( "-> Registering ComponentId of type: " + compId.getClass( ).getCanonicalName( ) );
      Components.create( compId );
    }
    
    /**
     * Create the component stubs (but do not startService) to do dependency checks on bootstrappers
     * and satisfy any forward references from bootstrappers.
     */
    LOG.info( LogUtil.header( "Building core local services: cloudLocal=" + BootstrapArgs.isCloudController( ) ) );
    List<Component> components = Components.whichCanLoad( );
    for ( Component comp : components ) {
      try {
        comp.initService( );
      } catch ( ServiceRegistrationException ex ) {
        LOG.info( ex.getMessage( ) );
      } catch ( Throwable ex ) {
        LOG.error( ex, ex );
      }
    }
    
    LOG.info( LogUtil.header( "Initializing component resources:" ) );
    for ( Component c : Components.whichCanLoad( ) ) {
      Bootstrap.applyTransition( c, Component.Transition.INITIALIZING );
    }
    
    LOG.info( LogUtil.header( "Initializing bootstrappers." ) );
    Bootstrap.initBootstrappers( );
    
    LOG.info( LogUtil.header( "System ready: starting bootstrap." ) );
    for ( Component c : Components.list( ) ) {
      LOG.info( c.toString( ) );
    }
  }
  
  public static int INIT_RETRIES = 5;
  
  public static void applyTransition( Component component, Component.Transition transition ) {
    StateMachine<ServiceConfiguration, State, Transition> fsm = component.getLocalServiceConfiguration( ).getStateMachine( );
    if ( fsm.isLegalTransition( transition ) ) {
      for ( int i = 0; i < INIT_RETRIES; i++ ) {
        try {
          EventRecord.caller( Bootstrap.class, EventType.COMPONENT_INFO, transition.name( ), component.getName( ), component.getComponentId( ) ).info( );
          fsm.transitionByName( transition ).get( );
          break;
        } catch ( ExistingTransitionException ex ) {
          LOG.error( ex );
        } catch ( Throwable ex ) {
          LOG.error( ex );
        }
        try {
          TimeUnit.MILLISECONDS.sleep( 50 );
        } catch ( InterruptedException ex ) {
          Thread.currentThread( ).interrupt( );
        }
      }
    }
    
  }

  static void initializeSystem( ) throws Throwable {
    GroovyUtil.evaluateScript( "initialize_cloud.groovy" );
  }
}
