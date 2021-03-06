/*************************************************************************
 * (c) Copyright 2016 Hewlett Packard Enterprise Development Company LP
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 ************************************************************************/
package com.eucalyptus.portal.awsusage;

import com.eucalyptus.compute.common.RunningInstancesItemType;

import com.eucalyptus.resources.client.Ec2Client;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;

public enum AwsUsageRecordType implements AwsUsageRecordTypeReader {
  UNKNOWN(null, null, null, AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(final String accountId, final List<QueuedEvent> events) {
      return Lists.newArrayList();
    }
  },

  EC2_RUNINSTANCE_BOX_USAGE("AmazonEC2", "RunInstances", "BoxUsage", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(final String accountId, final List<QueuedEvent> events) {
      // generate BoxUsage per instance types
      final List<QueuedEvent> instanceEvents = events.stream()
              .filter(e -> "InstanceUsage".equals(e.getEventType()))
              .collect(Collectors.toList());
      if (instanceEvents.size() <= 0)
        return Lists.newArrayList();

      final Map<String, String> instanceTypeMap = Maps.newHashMap();
      for (final String instanceId : instanceEvents.stream()
              .map(e -> e.getResourceId() )
              .distinct()
              .collect(Collectors.toList())) {
        try {
          final Optional<RunningInstancesItemType> instance =
                  Ec2Client.getInstance().describeInstances(null, Lists.newArrayList(instanceId)).stream()
                  .findFirst();
          if (instance.isPresent()) {
            instanceTypeMap.put(instance.get().getInstanceId(), instance.get().getInstanceType());
          }
        } catch (final Exception ex) {
          ;
        }
      }

      final Map<String, Integer> usagePerInstanceType = Maps.newHashMap();
      for (final String instanceId : instanceEvents.stream()
              .map(e -> e.getResourceId() )
              .distinct()
              .collect(Collectors.toList())) {
        if (instanceTypeMap.containsKey(instanceId)) {
          final String instanceType = instanceTypeMap.get(instanceId);
          if (!usagePerInstanceType.containsKey(instanceType)) {
            usagePerInstanceType.put(instanceType, 0);
          }
          usagePerInstanceType.put(instanceType,
                  usagePerInstanceType.get(instanceType) + 1);
        }
      }

      final Date earliestRecord = AwsUsageRecordType.getEarliest(instanceEvents);
      final List<AwsUsageRecord> records = Lists.newArrayList();

      final Date endTime = getNextHour(earliestRecord);
      final Date startTime = getPreviousHour(endTime);
      for (final String instanceType : usagePerInstanceType.keySet()) {
        final Integer usageValue = usagePerInstanceType.get(instanceType);
        final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
                .withService("AmazonEC2")
                .withOperation("RunInstances")
                .withResource(null)
                .withUsageType(String.format("BoxUsage:%s", instanceType))
                .withStartTime(startTime)
                .withEndTime(endTime)
                .withUsageValue(usageValue.toString())
                .build();
        records.add(data);
      }
      return records;
    }
  },
  EC2_CREATEVOLUME_VOLUME_USAGE("AmazonEC2", "CreateVolume", "VolumeUsage", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read ( final String accountId, final List<QueuedEvent> events){
      return sumDistinctResource(accountId, events, "VolumeUsage", "AmazonEC2", "CreateVolume", "EBS:VolumeUsage");
    }
  },
  EC2_CREATESNAPSHOT_SNAPSHOT_USAGE("AmazonEC2", "CreateSnapshot", "SnapshotUsage", AggregateGranularity.DAILY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      return sumDistinctResource(accountId, events, "SnapshotUsage", "AmazonEC2", "CreateSnapshot", "EBS:SnapshotUsage");
    }
  },
  EC2_ASSOCIATEADDRESS_ELASTIC_IP("AmazonEC2", "AssociateAddress", "ElasticIP", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,AssociateAddress,USW2-ElasticIP:IdleAddress,,11/15/16 14:00:00,11/15/16 15:00:00,1
      List<QueuedEvent> addressEvents = events.stream()
              .filter(e -> "AddressUsage".equals(e.getEventType()))
              .collect(Collectors.toList());
      if (addressEvents.size() <= 0)
        return Lists.newArrayList();

      addressEvents = AwsUsageRecordType.distinctByResourceIds(addressEvents);
      final List<AwsUsageRecord> records = Lists.newArrayList();
      final Date earliestRecord = AwsUsageRecordType.getEarliest(addressEvents);
      final Date endTime = getNextHour(earliestRecord);
      final Date startTime = getPreviousHour(endTime);
      final long countAddresses = addressEvents.stream()
              .count();
      if (countAddresses > 0) {
        /// TODO: Group by IdleAddress and AssociateAddress?
        final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
                .withService("AmazonEC2")
                .withOperation("AssociateAddress")
                .withResource(null)
                .withUsageType("ElasticIP:IdleAddress")
                .withStartTime(startTime)
                .withEndTime(endTime)
                .withUsageValue(String.format("%d", countAddresses))
                .build();
        records.add(data);
      }
      return records;
    }
  },

  S3_STORAGE_OBJECT_COUNT("AmazonS3", "StandardStorage", "StorageObjectCount", AggregateGranularity.DAILY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
// AmazonS3,StandardStorage,StorageObjectCount,spark-billing-test01,11/26/16 08:00:00,11/26/16 09:00:00,86
      List<QueuedEvent> objectEvents = events.stream()
              .filter(e -> "S3ObjectUsage".equals(e.getEventType()))
              .filter(e -> e.getResourceId()!=null && e.getResourceId().contains("/"))
              .collect(Collectors.toList());
      if (objectEvents.size() <= 0)
        return Lists.newArrayList();

      final Date earliestRecord = AwsUsageRecordType.getEarliest(objectEvents);
      final Date endTime = getNextHour(earliestRecord);
      final Date startTime = getPreviousHour(endTime);
      final List<AwsUsageRecord> records = Lists.newArrayList();

      final Map<String, Long> objectCounter =
              AwsUsageRecordType.distinctByResourceIds(objectEvents).stream()
              .map(e -> e.getResourceId().split("/")[0]) // bucket name
              .collect( groupingBy(Function.identity(), counting() ));

      for (final String bucket : objectCounter.keySet()) {
        final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
                .withService("AmazonS3")
                .withOperation("StandardStorage")
                .withResource(bucket)
                .withUsageType("StorageObjectCount")
                .withStartTime(startTime)
                .withEndTime(endTime)
                .withUsageValue(String.format("%d", objectCounter.get(bucket)))
                .build();
        records.add(data);
      }
      return records;
    }
  },

  S3_STORAGE_OBJECT_BYTEHRS("AmazonS3", "StandardStorage", "TimedStorage-ByteHrs", AggregateGranularity.DAILY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
     // AmazonS3,StandardStorage,USW2-TimedStorage-ByteHrs,billing-test-bucket-tmp,11/26/16 08:00:00,11/26/16 09:00:00,4964856
      List<QueuedEvent> objectEvents = events.stream()
              .filter(e -> "S3ObjectUsage".equals(e.getEventType()))
              .filter(e -> e.getResourceId()!=null && e.getResourceId().contains("/"))
              .collect(Collectors.toList());
      if (objectEvents.size() <= 0)
        return Lists.newArrayList();

      final Date earliestRecord = AwsUsageRecordType.getEarliest(objectEvents);
      final Date endTime = getNextHour(earliestRecord);
      final Date startTime = getPreviousHour(endTime);
      final List<AwsUsageRecord> records = Lists.newArrayList();

      final Map<String, Long> usageBytes =
              AwsUsageRecordType.distinctByResourceIds(objectEvents).stream()
              .collect( groupingBy( e -> e.getResourceId().split("/")[0] ,
                      summingLong( e -> Long.parseLong(e.getUsageValue()))));

      for (final String bucket : usageBytes.keySet()) {
        final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
                .withService("AmazonS3")
                .withOperation("StandardStorage")
                .withResource(bucket)
                .withUsageType("TimedStorage-ByteHrs")
                .withStartTime(startTime)
                .withEndTime(endTime)
                .withUsageValue(String.format("%d", usageBytes.get(bucket)))
                .build();
        records.add(data);
      }
      return records;
    }
  },
  EC2_EBS_VolumeIORead("AmazonEC2", "EBS:IO-Read", "EBS:VolumeIOUsage", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,EBS:Gp2-IO-Read,USW2-EBS:VolumeIOUsage.gp2,,11/10/16 00:00:00,11/11/16 00:00:00,4
      return sum(accountId, events, "EBS:VolumeIOUsage-Read", "AmazonEC2", "EBS:IO-Read", "EBS:VolumeIOUsage");
    }
  },
  EC2_EBS_VolumeIOWrite("AmazonEC2", "EBS:IO-Write", "EBS:VolumeIOUsage", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,EBS:Gp2-IO-Write,USW2-EBS:VolumeIOUsage.gp2,,11/10/16 00:00:00,11/11/16 00:00:00,514
      return sum(accountId, events, "EBS:VolumeIOUsage-Write", "AmazonEC2", "EBS:IO-Write", "EBS:VolumeIOUsage");
    }
  },
  EC2_INSTANCE_DATATRANSFER_IN("AmazonEC2", "RunInstances", "DataTransfer-In-Bytes", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,RunInstances,USW2-DataTransfer-In-Bytes,,11/10/16 00:00:00,11/11/16 00:00:00,13971
      return sum(accountId, events, "InstanceDataTransfer-In", "AmazonEC2", "RunInstances", "DataTransfer-In-Bytes");
    }
  },
  EC2_INSTANCE_DATATRANSFER_OUT("AmazonEC2", "RunInstances", "DataTransfer-Out-Bytes", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,RunInstances,USW2-DataTransfer-Out-Bytes,,11/10/16 00:00:00,11/11/16 00:00:00,13395
      return sum(accountId, events, "InstanceDataTransfer-Out", "AmazonEC2", "RunInstances", "DataTransfer-Out-Bytes");
    }
  },
  EC2_PUBLICIP_IN("AmazonEC2", "PublicIP-In", "AWS-In-Bytes", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,PublicIP-In,USW2-USE2-AWS-In-Bytes,,11/22/16 00:00:00,11/23/16 00:00:00,80
      return sum( accountId, events, "InstancePublicIpTransfer-In", "AmazonEC2", "PublicIP-In", "AWS-In-Bytes");
    }
  },
  EC2_PUBLICIP_OUT("AmazonEC2", "PublicIP-Out", "AWS-Out-Bytes", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,PublicIP-Out,USW2-USE1-AWS-Out-Bytes,,11/19/16 00:00:00,11/20/16 00:00:00,40
      return sum(accountId, events, "InstancePublicIpTransfer-Out", "AmazonEC2", "PublicIP-Out", "AWS-Out-Bytes");
    }
  },
  EC2_LOADBALANCER_DATATRANSFER_IN("AmazonEC2", "LoadBalancing", "DataTransfer-In-Bytes", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,LoadBalancing,USW2-DataTransfer-ELB-In-Bytes,,11/18/16 00:00:00,11/19/16 00:00:00,11879589
     return sum(accountId, events, "LoadBalancing-DataTransfer-In", "AmazonEC2", "LoadBalancing", "DataTransfer-In-Bytes");
    }
  },
  EC2_LOADBALANCER_DATATRANSFER_OUT("AmazonEC2", "LoadBalancing", "DataTransfer-Out-Bytes", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,LoadBalancing,USW2-DataTransfer-ELB-Out-Bytes,,11/18/16 00:00:00,11/19/16 00:00:00,3144252
      return sum(accountId, events, "LoadBalancing-DataTransfer-Out", "AmazonEC2", "LoadBalancing", "DataTransfer-Out-Bytes");
    }
  },
  EC2_LOADBALANCER_USAGE("AmazonEC2", "LoadBalancing", "LoadBalancerUsage", AggregateGranularity.HOURLY) {
    @Override
    public List<AwsUsageRecord> read(String accountId, List<QueuedEvent> events) {
      // AmazonEC2,LoadBalancing,USW2-LoadBalancerUsage,,11/22/16 00:00:00,11/22/16 01:00:00,1
      List<QueuedEvent> filteredEvents = events.stream()
              .filter(e -> "LoadBalancerUsage".equals(e.getEventType()))
              .collect(Collectors.toList());
      if (filteredEvents.size() <= 0)
        return Lists.newArrayList();
      filteredEvents = AwsUsageRecordType.distinctByResourceIds(filteredEvents);
      final Date earliestRecord = AwsUsageRecordType.getEarliest(filteredEvents);
      final Date endTime = getNextHour(earliestRecord);
      final Date startTime = getPreviousHour(endTime);

      final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
              .withService("AmazonEC2")
              .withOperation("LoadBalancing")
              .withResource(null)
              .withUsageType("LoadBalancerUsage")
              .withStartTime(startTime)
              .withEndTime(endTime)
              .withUsageValue(String.format("%d", filteredEvents.size()))
              .build();
      return Lists.newArrayList(data);
    }
  };

  private String service = null;
  private String operation = null;
  private String usageType = null;
  private AggregateGranularity granularity = AggregateGranularity.HOURLY;
  AwsUsageRecordType(final String service, final String operation, final String usageType, final AggregateGranularity granularity) {
    this.service = service;
    this.operation = operation;
    this.usageType = usageType;
    this.granularity = granularity;
  }

  public AggregateGranularity getGranularity() {
    return this.granularity;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    if (service !=null)
      sb.append(String.format("%s:", service));
    else
      sb.append(":");
    if (operation !=null)
      sb.append(String.format("%s:", operation));
    else
      sb.append(":");
    if (usageType != null)
      sb.append(String.format("%s", usageType));
    return sb.toString();
  }

  public static AwsUsageRecordType forValue(final String value) {
    final String[] tokens = value.split(":");
    if (tokens==null || tokens.length != 3) {
      return UNKNOWN;
    }
    final String vService = tokens[0];
    final String vOperation = tokens[1];
    final String vUsageType = tokens[2];
    for (final AwsUsageRecordType type : AwsUsageRecordType.values()) {
      if (type.service == null) {
        if (vService!=null)
          continue;
      } else if (!type.service.equals(vService)) {
          continue;
      }

      if(type.operation == null) {
        if (vOperation != null)
          continue;
      } else if(!type.operation.equals(vOperation)) {
          continue;
      }

      if(type.usageType == null) {
        if(vUsageType != null)
          continue;
      } else if(!type.usageType.equals(vUsageType)) {
        continue;
      }

      return type;
    }

    return UNKNOWN;
  }

  private static List<AwsUsageRecord> sumDistinctResource( final String accountId, final List<QueuedEvent> events,
                                                         final String eventType, final String service, final String operation,
                                                         final String usageType) {
    List<QueuedEvent> filteredEvents = events.stream()
            .filter(e -> eventType.equals(e.getEventType()))
            .collect(Collectors.toList());
    if (filteredEvents.size() <= 0)
      return Lists.newArrayList();

    filteredEvents = AwsUsageRecordType.distinctByResourceIds(filteredEvents);
    final List<AwsUsageRecord> records = Lists.newArrayList();
    final Date earliestRecord = AwsUsageRecordType.getEarliest(filteredEvents);
    final Date endTime = getNextHour(earliestRecord);
    final Date startTime = getPreviousHour(endTime);

    final Optional<Long> value = filteredEvents.stream()
            .map( e -> Long.parseLong(e.getUsageValue()) )
            .reduce( (l1, l2) -> l1+l2 );
    if (value.isPresent()) {
      final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
              .withService(service)
              .withOperation(operation)
              .withResource(null)
              .withUsageType(usageType)
              .withStartTime(startTime)
              .withEndTime(endTime)
              .withUsageValue(value.get().toString())
              .build();
      records.add(data);
    }
    return records;
  }

  private static List<AwsUsageRecord> sum(final String accountId, final List<QueuedEvent> events,
                                            final String eventType, final String service, final String operation,
                                           final String usageType) {
    final List<QueuedEvent> filteredEvents = events.stream()
            .filter(e -> eventType.equals(e.getEventType()))
            .collect(Collectors.toList());
    if (filteredEvents.size() <= 0)
      return Lists.newArrayList();

    final Date earliestRecord = AwsUsageRecordType.getEarliest(filteredEvents);
    final Date endTime = getNextHour(earliestRecord);
    final Date startTime = getPreviousHour(endTime);

    /// sum over all transfer-out bytes
    final Optional<Long> usageSum = filteredEvents.stream()
            .map(e -> Long.parseLong(e.getUsageValue()))
            .reduce( (l1, l2) -> l1+l2 );

    if (usageSum.isPresent()) {
      final AwsUsageRecord data = AwsUsageRecords.getInstance().newRecord(accountId)
              .withService(service)
              .withOperation(operation)
              .withResource(null)
              .withUsageType(usageType)
              .withStartTime(startTime)
              .withEndTime(endTime)
              .withUsageValue(String.format("%d", usageSum.get()))
              .build();
      return Lists.newArrayList(data);
    }
    return Lists.newArrayList();
  }

  private static List<QueuedEvent> distinctByResourceIds(final List<QueuedEvent> events) {
    final Map<String, QueuedEvent> uniqueEvents = Maps.newHashMap();
    events.stream().forEach( evt -> uniqueEvents.put(evt.getResourceId(), evt) );
    return Lists.newArrayList(uniqueEvents.values());
  }

  private static Date getEarliest(final  List<QueuedEvent> events) {
    final Date earliestRecord = events.stream()
            .map( e -> e.getTimestamp())
            .min((a, b) -> a.before(b) ? -1 : 1)
            .get();
    return earliestRecord;
  }
  private static Date getNextHour(final Date time) {
    final Calendar c = Calendar.getInstance();
    c.setTime(time);
    c.set(Calendar.HOUR, c.get(Calendar.HOUR) + 1);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTime();
  }

  private static Date getPreviousHour(final Date time) {
    final Calendar c = Calendar.getInstance();
    c.setTime(time);
    c.set(Calendar.HOUR, c.get(Calendar.HOUR) - 1);
    return c.getTime();
  }
}
