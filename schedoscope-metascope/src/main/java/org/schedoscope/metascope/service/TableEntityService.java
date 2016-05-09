/**
 * Copyright 2015 Otto (GmbH & Co KG)
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
package org.schedoscope.metascope.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.schedoscope.metascope.index.SolrFacade;
import org.schedoscope.metascope.model.CategoryMap;
import org.schedoscope.metascope.model.CategoryObjectEntity;
import org.schedoscope.metascope.model.CommentEntity;
import org.schedoscope.metascope.model.FieldEntity;
import org.schedoscope.metascope.model.ParameterValueEntity;
import org.schedoscope.metascope.model.TableDependencyEntity;
import org.schedoscope.metascope.model.TableEntity;
import org.schedoscope.metascope.model.UserEntity;
import org.schedoscope.metascope.model.ViewEntity;
import org.schedoscope.metascope.repository.CategoryObjectEntityRepository;
import org.schedoscope.metascope.repository.ParameterValueEntityRepository;
import org.schedoscope.metascope.repository.TableDependencyEntityRepository;
import org.schedoscope.metascope.repository.TableEntityRepository;
import org.schedoscope.metascope.repository.UserEntityRepository;
import org.schedoscope.metascope.repository.ViewEntityRepository;
import org.schedoscope.metascope.repository.spec.ParameterValueSpec;
import org.schedoscope.metascope.util.HTMLUtil;
import org.schedoscope.metascope.util.HiveQueryExecutor;
import org.schedoscope.metascope.util.HiveQueryResult;
import org.schedoscope.metascope.util.LineageUtil;
import org.schedoscope.metascope.util.SampleCacheLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;

@Service
public class TableEntityService {

  private static final Logger LOG = LoggerFactory.getLogger(TableEntityService.class);

  @Autowired
  private ActivityEntityService activityEntityService;
  @Autowired
  private UserEntityService userEntityService;
  @Autowired
  private ViewEntityService viewEntityService;
  @Autowired
  private DataDistributionService dataDistributionService;
  @Autowired
  private TableEntityRepository tableEntityRepository;
  @Autowired
  private UserEntityRepository userEntityRepository;
  @Autowired
  private CategoryObjectEntityRepository categoryObjectEntityRepository;
  @Autowired
  private ViewEntityRepository viewEntityRepository;
  @Autowired
  private ParameterValueEntityRepository parameterValueEntityRepository;
  @Autowired
  private TableDependencyEntityRepository tableDependencyEntityRepository;
  @Autowired
  @Lazy
  private SolrFacade solr;
  @Autowired
  private JobSchedulerService jobSchedulerService;
  @Autowired
  private HiveQueryExecutor hiveUtil;
  @Autowired
  private LineageUtil lineageUtil;
  @Autowired
  private HTMLUtil htmlUtil;

  private LoadingCache<String, HiveQueryResult> sampleCache;

  @PostConstruct
  public void init() {
    this.sampleCache = CacheBuilder.newBuilder().maximumSize(1000).expireAfterWrite(1, TimeUnit.DAYS)
        .build(new SampleCacheLoader(this, hiveUtil));
  }

  @Transactional
  public void setPersonResponsible(String fqdn, String fullname) {
    TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);
    UserEntity userEntity = userEntityService.findByFullname(fullname);
    if (tableEntity != null) {
      if (tableEntity.getPersonResponsible() == null || !tableEntity.getPersonResponsible().equals(fullname)) {
        if (userEntity != null) {
          tableEntity.setPersonResponsible(userEntity.getFullname());
          tableEntityRepository.save(tableEntity);
          LOG.info("User '{}' changed responsible person for table '{}' to '{}'", userEntityService.getUser()
              .getUsername(), fqdn, fullname);
          activityEntityService.createUpdateTableMetadataActivity(tableEntity, userEntityService.getUser());
        } else if (!fullname.isEmpty()) {
          tableEntity.setPersonResponsible(fullname);
          tableEntityRepository.save(tableEntity);
          LOG.info("User '{}' changed responsible person for table '{}' to '{}'", userEntityService.getUser()
              .getUsername(), fqdn, fullname);
          activityEntityService.createUpdateTableMetadataActivity(tableEntity, userEntityService.getUser());
        }
      }
    }
  }

  @Transactional
  public void setTimestampField(String fqdn, String dataTimestampField, String dataTimestampFieldFormat) {
    TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);
    if (tableEntity != null && !dataTimestampField.isEmpty()) {
      String oldTimestampField = tableEntity.getTimestampField();
      tableEntity.setTimestampField(dataTimestampField);
      if (!dataTimestampFieldFormat.isEmpty()) {
        tableEntity.setTimestampFieldFormat(dataTimestampFieldFormat);
      }
      tableEntityRepository.save(tableEntity);
      LOG.info("User '{}' changed timestamp field for table '{}' to '{}' with format '{}'", userEntityService.getUser()
          .getUsername(), fqdn, dataTimestampField, dataTimestampFieldFormat);
      activityEntityService.createUpdateTableMetadataActivity(tableEntity, userEntityService.getUser());
      if (oldTimestampField != dataTimestampField) {
        jobSchedulerService.updateLastDataForTable(tableEntity);
      }
    }
  }

  @Transactional
  public void addOrRemoveFavourite(String fqdn) {
    UserEntity user = userEntityService.getUser();
    if (user.getFavourites() == null) {
      user.setFavourites(new ArrayList<String>());
    }
    boolean removed = user.getFavourites().remove(fqdn);
    if (!removed) {
      user.getFavourites().add(fqdn);
    }
    userEntityRepository.save(user);
  }

  @Transactional
  public void increaseViewCount(String fqdn) {
    TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);
    tableEntity.setViewCount(tableEntity.getViewCount() + 1);
    tableEntityRepository.save(tableEntity);
  }
  
  @Transactional
	public void setCategoryObjects(String fqdn, Map<String, String[]> parameterMap) {
	  TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);
	
	  if (tableEntity == null) {
	    return;
	  }
	  
	  tableEntity.getCategoryObjects().clear();
	  
	  String categoryObjectList = "";
	  for (Entry<String, String[]> e : parameterMap.entrySet()) {
	  	if (!e.getKey().endsWith("CategoryObjects")) {
	  		continue;
	  	}
	  	
	  	String categoryObjectIds = e.getValue()[0];
	    String[] categoryObjects = categoryObjectIds.split(",");
	    for (String categoryObjectId : categoryObjects) {
	    	if (categoryObjectId.isEmpty()) {
	    		continue;
	    	}
	    	
		    CategoryObjectEntity categoryObjectEntity = categoryObjectEntityRepository.findOne(Long.parseLong(categoryObjectId));
		    if (categoryObjectEntity != null) {
		    	tableEntity.getCategoryObjects().add(categoryObjectEntity);
		    	if (!categoryObjectList.isEmpty()) {
		    		categoryObjectList += ", ";
		    	}
		    	categoryObjectList += categoryObjectEntity.getName();
		    }
      }
    }
	  
	  tableEntityRepository.save(tableEntity);
	  //solr.updateTableEntityAsync(tableEntity, true);
	  LOG.info("User '{}' changed category objects for table '{}' to '{}'", userEntityService.getUser().getUsername(),
	      fqdn, categoryObjectList);
	  activityEntityService.createUpdateTaxonomyActivity(tableEntity, userEntityService.getUser());
	  
  }

  @Transactional
  public void setBusinessObjects(String fqdn, String businessObjectsCommaDelimited) {
//    TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);
//
//    if (tableEntity == null) {
//      return;
//    }
//
//    if (businessObjectsCommaDelimited == null) {
//      businessObjectsCommaDelimited = "";
//    }
//
//    String[] businessObjects = businessObjectsCommaDelimited.split(",");
//
//    Iterable<BusinessObjectEntity> repositoryBos = businessObjectEntityRepository.findAll();
//    tableEntity.getBusinessObjects().clear();
//
//    for (String bo : businessObjects) {
//      for (BusinessObjectEntity repoBo : repositoryBos) {
//        if (bo.equals(repoBo.getName())) {
//          if (!tableEntity.getBusinessObjects().contains(repoBo)) {
//            tableEntity.getBusinessObjects().add(repoBo);
//          }
//        }
//      }
//    }
//
//    tableEntityRepository.save(tableEntity);
//    solr.updateTableEntityAsync(tableEntity, true);
//    LOG.info("User '{}' changed business objects for table '{}' to '{}'", userEntityService.getUser().getUsername(),
//        fqdn, businessObjectsCommaDelimited);
//    activityEntityService.createUpdateTaxonomyActivity(tableEntity, userEntityService.getUser());
  }

  @Transactional
  public void setTags(String fqdn, String tagsCommaDelimited) {
    TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);

    if (tableEntity == null) {
      return;
    }

    if (tagsCommaDelimited == null) {
      tagsCommaDelimited = "";
    }

    String[] tags = tagsCommaDelimited.split(",");
    tableEntity.getTags().clear();
    for (String tag : tags) {
      if (!tag.isEmpty()) {
        tableEntity.getTags().add(tag);
      }
    }
    tableEntityRepository.save(tableEntity);
    solr.updateTableEntityAsync(tableEntity, true);
    LOG.info("User '{}' changed tags for table '{}' to '{}'", userEntityService.getUser().getUsername(), fqdn,
        tagsCommaDelimited);
    activityEntityService.createUpdateTaxonomyActivity(tableEntity, userEntityService.getUser());
  }

  public TableEntity findByFqdn(String tablefqdn) {
    if (tablefqdn == null) {
      return null;
    }

    return tableEntityRepository.findByFqdn(tablefqdn);
  }

  public TableEntity findByComment(CommentEntity commentEntity) {
    return tableEntityRepository.findByComment(commentEntity);
  }

  public String getLineage(TableEntity tableEntity) {
    return lineageUtil.getLineage(tableEntity);
  }

  public Map<String, List<String>> getParameterValues(TableEntity table) {
    Map<String, List<String>> parameterValues = new HashMap<String, List<String>>();
    List<Object[]> distinctValues = parameterValueEntityRepository.findDistinctParameterValues(table.getFqdn());
    for (int i = distinctValues.size() - 1; i >= 0; i--) {
      Object[] parameterValue = distinctValues.get(i);
      String key = (String) parameterValue[0];
      String value = (String) parameterValue[1];
      List<String> list = parameterValues.get(key);
      if (list == null) {
        list = new ArrayList<String>();
      }
      list.add(value);
      parameterValues.put(key, list);
    }
    return parameterValues;
  }

  @Transactional
  public String getRandomParameterValue(TableEntity tableEntity, FieldEntity parameter) {
    ViewEntity viewEntity = viewEntityRepository.findFirstByFqdn(tableEntity.getFqdn());
    List<ParameterValueEntity> parameters = parameterValueEntityRepository.findByKeyUrlPath(viewEntity.getUrlPath());
    for (ParameterValueEntity parameterValueEntity : parameters) {
      if (parameterValueEntity.getKey().equals(parameter.getName())) {
        return parameterValueEntity.getValue();
      }
    }
    return null;
  }

  @Transactional
  public Set<String> getParameterValues(TableEntity tableEntity, String urlPathPrefix, String next) {
    String fqdn = tableEntity.getFqdn();
    List<ParameterValueEntity> params = parameterValueEntityRepository.findAll(
        ParameterValueSpec.queryWithParams(fqdn, urlPathPrefix, next), new Sort(Sort.Direction.DESC, "value"));
    Set<String> values = new LinkedHashSet<String>();
    for (ParameterValueEntity parameterValueEntity : params) {
      if (!values.contains(parameterValueEntity.getValue())) {
        values.add(parameterValueEntity.getValue());
      }
    }
    return values;
  }

  @Async
  public Future<HiveQueryResult> getSample(String fqdn, Map<String, String> params) {
    if (params == null || params.isEmpty()) {
      return new AsyncResult<HiveQueryResult>(sampleCache.getUnchecked(fqdn));
    } else {
      TableEntity tableEntity = tableEntityRepository.findByFqdn(fqdn);
      return new AsyncResult<HiveQueryResult>(hiveUtil.executeQuery(fqdn, tableEntity.getFieldsCommaDelimited(),
          tableEntity.getParameters(), params));
    }
  }

  @Transactional
  public ViewEntity runDataDistribution(TableEntity tableEntity, String selectedPartition, int partitionCount) {
    if (partitionCount == 1) {
      ViewEntity viewEntity = tableEntity.getViews().get(0);
      if (!viewEntity.isProcessed() && !viewEntity.isProcessing()) {
        dataDistributionService.calculateDistributionForView(viewEntity.getUrlPath());
        return null;
      }
    }

    if (partitionCount > 1 && selectedPartition != null) {
      ViewEntity viewEntity = viewEntityService.findByUrlPath(selectedPartition);
      if (viewEntity != null) {
        if (!viewEntity.isProcessed() && !viewEntity.isProcessing()) {
          dataDistributionService.calculateDistributionForView(viewEntity.getUrlPath());
        }
        return viewEntity;
      }
    }

    return null;
  }

  public List<TableDependencyEntity> getTransitiveDependencies(TableEntity tableEntity) {
    List<TableDependencyEntity> dependencies = new ArrayList<TableDependencyEntity>();
    List<String> visitedTables = new ArrayList<String>();
    for (TableDependencyEntity dependencyEntity : tableEntity.getDependencies()) {
      getRecursiveDependencies(dependencies, visitedTables, dependencyEntity);
    }
    return dependencies;
  }

  private void getRecursiveDependencies(List<TableDependencyEntity> dependencies, List<String> visitedTables,
      TableDependencyEntity dependency) {
    TableEntity tableEntity = tableEntityRepository.findByFqdn(dependency.getDependencyFqdn());
    if (!visitedTables.contains(tableEntity.getFqdn())) {
      dependencies.add(dependency);
      visitedTables.add(tableEntity.getFqdn());
      for (TableDependencyEntity dependencyEntity : tableEntity.getDependencies()) {
        boolean alreadyContained = false;
        for (TableDependencyEntity d : dependencies) {
          if (d.getDependencyFqdn().equals(dependencyEntity.getDependencyFqdn())) {
            alreadyContained = true;
          }
        }
        if (!alreadyContained) {
          getRecursiveDependencies(dependencies, visitedTables, dependencyEntity);
        }
      }
    }
  }

  public List<TableDependencyEntity> getTransitiveSuccessors(TableEntity tableEntity) {
    List<TableDependencyEntity> successors = new ArrayList<TableDependencyEntity>();
    List<String> visitedTables = new ArrayList<String>();
    for (TableDependencyEntity successorEntity : getSuccessors(tableEntity)) {
      getRecursiveSuccessors(successors, visitedTables, successorEntity);
    }
    return successors;
  }

  private void getRecursiveSuccessors(List<TableDependencyEntity> successors, List<String> visitedTables,
      TableDependencyEntity successor) {
    TableEntity tableEntity = tableEntityRepository.findByFqdn(successor.getFqdn());
    if (!visitedTables.contains(tableEntity.getFqdn())) {
      successors.add(successor);
      visitedTables.add(tableEntity.getFqdn());
      for (TableDependencyEntity successorEntity : getSuccessors(tableEntity)) {
        boolean alreadyContained = false;
        for (TableDependencyEntity s : successors) {
          if (s.getFqdn().equals(successorEntity.getFqdn())) {
            alreadyContained = true;
          }
        }
        if (!alreadyContained) {
          getRecursiveSuccessors(successors, visitedTables, successorEntity);
        }
      }
    }
  }

  public List<TableEntity> getTopFiveTables() {
    return tableEntityRepository.findTop5ByOrderByViewCountDesc();
  }

  public Page<ViewEntity> getRequestedViewPage(TableEntity tableEntity, Pageable pageable) {
    return getRequestedViewPage(tableEntity.getFqdn(), pageable);
  }

  public Page<ViewEntity> getRequestedViewPage(String fqdn, Pageable pageable) {
    return viewEntityRepository.findByFqdnOrderByInternalViewId(fqdn, pageable);
  }

  public List<TableDependencyEntity> getSuccessors(TableEntity tableEntity) {
    return tableDependencyEntityRepository.getSuccessorsForFqdn(tableEntity.getFqdn());
  }

  public Set<String> getAllOwner() {
    return tableEntityRepository.getAllOwner();
  }

	public Map<String, CategoryMap> getTableTaxonomies(TableEntity tableEntity) {
//		Map<String, Map<String, String>> result = new HashMap<String, Map<String, String>>();
//		
//		Map<String, Map<List<String>, List<String>>> taxonomies = new HashMap<String, Map<List<String>, List<String>>>();
//		for (CategoryObjectEntity categoryObjectEntity : tableEntity.getCategoryObjects()) {
//	    String taxonomyName = categoryObjectEntity.getCategory().getTaxonomy().getName();
//	    Map<List<String>, List<String>> categoryMap = taxonomies.get(taxonomyName);
//	    if (categoryMap == null) {
//	    	categoryMap = new HashMap<List<String>, List<String>>();
//	    }
//	    
//	    List<String> categoryNames = null;
//	    List<String> categoryObjectNames = null;
//	    Iterator<Entry<List<String>, List<String>>> iterator = categoryMap.entrySet().iterator();
//	    if (!iterator.hasNext()) {
//	    	categoryNames = new ArrayList<String>();
//	    	categoryObjectNames = new ArrayList<String>();
//	    } else {
//	    	Entry<List<String>, List<String>> categoryEntry = iterator.next();
//	    	categoryNames = categoryEntry.getKey();
//		    categoryObjectNames = categoryEntry.getValue();
//	    }
//	    
//	    String categoryName = categoryObjectEntity.getCategory().getName();
//	    if (!categoryNames.contains(categoryName)) {
//	    	categoryNames.add(categoryName);
//	    }
//	    
//	    String categoryObjectName = categoryObjectEntity.getName();
//	    if (!categoryObjectNames.contains(categoryObjectName)) {
//	    	categoryObjectNames.add(categoryObjectName);
//	    }
//	    
//	    categoryMap.clear();
//	    categoryMap.put(categoryNames, categoryObjectNames);
//	    taxonomies.put(taxonomyName, categoryMap);
//    }
//		
//		for (Entry<String, Map<List<String>, List<String>>> e : taxonomies.entrySet()) {
//	    Entry<List<String>, List<String>> categoryMap = e.getValue().entrySet().iterator().next();
//	    List<String> categories = categoryMap.getKey();
//	    List<String> categoryObjects = categoryMap.getValue();
//	    
//	    String categoriesCommaDelimited = "";
//	    String categoryObjectCommaDelimited = "";
//	    
//	    for (String categoryName : categories) {
//	      if (!categoriesCommaDelimited.isEmpty()) {
//	      	categoriesCommaDelimited += ",";
//	      }
//	      categoriesCommaDelimited += categoryName;
//      }
//	    
//	    for (String categoryObjectName : categoryObjects) {
//	      if (!categoriesCommaDelimited.isEmpty()) {
//	      	categoryObjectCommaDelimited += ",";
//	      }
//	      categoryObjectCommaDelimited += categoryObjectName;
//      }
//	    
//	    Map<String, String> res = new HashMap<String, String>();
//	    res.put(categoriesCommaDelimited, categoryObjectCommaDelimited);
//    }
//		
//		return result;
		
		Map<String, CategoryMap> taxonomies = new LinkedHashMap<String, CategoryMap>();
		
		for (CategoryObjectEntity categoryObjectEntity : tableEntity.getCategoryObjects()) {
	    String taxonomyName = categoryObjectEntity.getCategory().getTaxonomy().getName();
	    CategoryMap categoryMap = taxonomies.get(taxonomyName);
	    if (categoryMap == null) {
	    	categoryMap = new CategoryMap();
	    }
	    categoryMap.addToCategories(categoryObjectEntity.getCategory());
	    categoryMap.addToCategoryObjects(categoryObjectEntity);
	    taxonomies.put(taxonomyName, categoryMap);
	  }

		return taxonomies;
  }

}
