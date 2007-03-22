/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivy.plugins.resolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.core.settings.IvyPattern;
import org.apache.ivy.plugins.latest.LatestStrategy;
import org.apache.ivy.plugins.resolver.util.MDResolvedResource;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;
import org.apache.ivy.plugins.resolver.util.ResourceMDParser;
import org.apache.ivy.plugins.version.VersionMatcher;
import org.apache.ivy.util.Message;


/**
 * @author Xavier Hanin
 *
 */
public abstract class AbstractResourceResolver extends BasicResolver {
    
    private static final Map IVY_ARTIFACT_ATTRIBUTES = new HashMap();
    static {
        IVY_ARTIFACT_ATTRIBUTES.put(IvyPatternHelper.ARTIFACT_KEY, "ivy");
        IVY_ARTIFACT_ATTRIBUTES.put(IvyPatternHelper.TYPE_KEY, "ivy");
        IVY_ARTIFACT_ATTRIBUTES.put(IvyPatternHelper.EXT_KEY, "xml");
    }
    
    private List _ivyPatterns = new ArrayList(); // List (String pattern)
    private List _artifactPatterns = new ArrayList();  // List (String pattern)
    private boolean _m2compatible = false;

    
    public AbstractResourceResolver() {
    }

    protected ResolvedResource findIvyFileRef(DependencyDescriptor dd, ResolveData data) {
        ModuleRevisionId mrid = dd.getDependencyRevisionId();
        if (isM2compatible()) {
            mrid = convertM2IdForResourceSearch(mrid);
        }
        return findResourceUsingPatterns(mrid, _ivyPatterns, DefaultArtifact.newIvyArtifact(mrid, data.getDate()), getRMDParser(dd, data), data.getDate());
    }

	protected ResolvedResource findArtifactRef(Artifact artifact, Date date) {
        ModuleRevisionId mrid = artifact.getModuleRevisionId();
        if (isM2compatible()) {
            mrid = convertM2IdForResourceSearch(mrid);
        }
        return findResourceUsingPatterns(mrid, _artifactPatterns, artifact, getDefaultRMDParser(artifact.getModuleRevisionId().getModuleId()), date);
    }

	protected ResolvedResource findResourceUsingPatterns(ModuleRevisionId moduleRevision, List patternList, Artifact artifact, ResourceMDParser rmdparser, Date date) {
        ResolvedResource rres = null;
        
        List resolvedResources = new ArrayList();
        boolean dynamic = getSettings().getVersionMatcher().isDynamic(moduleRevision);
        boolean stop = false;
        for (Iterator iter = patternList.iterator(); iter.hasNext() && !stop;) {
            String pattern = (String)iter.next();
            rres = findResourceUsingPattern(moduleRevision, pattern, artifact, rmdparser, date);
            if (rres != null) {
            	resolvedResources.add(rres);
            	stop = !dynamic; // stop iterating if we are not searching a dynamic revision
            }
        }
        
        if (resolvedResources.size() > 1) {
        	ResolvedResource[] rress = (ResolvedResource[]) resolvedResources.toArray(new ResolvedResource[resolvedResources.size()]);
        	rres = findResource(rress, getName(), getLatestStrategy(), getSettings().getVersionMatcher(), rmdparser, moduleRevision, date);
        }
        
        return rres;
    }
    
    protected abstract ResolvedResource findResourceUsingPattern(ModuleRevisionId mrid, String pattern, Artifact artifact, ResourceMDParser rmdparser, Date date);

    public static ResolvedResource findResource(
    		ResolvedResource[] rress, 
    		String name,
    		LatestStrategy strategy, 
    		VersionMatcher versionMatcher, 
    		ResourceMDParser rmdparser,
    		ModuleRevisionId mrid, 
    		Date date) {
    	ResolvedResource found = null;
    	List sorted = strategy.sort(rress);
    	for (ListIterator iter = sorted.listIterator(sorted.size()); iter.hasPrevious();) {
			ResolvedResource rres = (ResolvedResource) iter.previous();
			if ((date != null && rres.getLastModified() > date.getTime())) {
                Message.debug("\t"+name+": too young: "+rres);
				continue;
			}
			ModuleRevisionId foundMrid = ModuleRevisionId.newInstance(mrid, rres.getRevision());
			if (!versionMatcher.accept(mrid, foundMrid)) {
                Message.debug("\t"+name+": rejected by version matcher: "+rres);
				continue;
			}
			if (versionMatcher.needModuleDescriptor(mrid, foundMrid)) {
        		ResolvedResource r = rmdparser.parse(rres.getResource(), rres.getRevision());
        		ModuleDescriptor md = ((MDResolvedResource)r).getResolvedModuleRevision().getDescriptor();
        		if (md.isDefault()) {
	                Message.debug("\t"+name+": default md rejected by version matcher requiring module descriptor: "+rres);
        			continue;
        		} else if (!versionMatcher.accept(mrid, md)) {
	                Message.debug("\t"+name+": md rejected by version matcher: "+rres);
        			continue;
        		} else {
        			found = r;
        		}
			} else {
				found = rres;
			}
	    	
	    	if (found != null) {
	    		if (!found.getResource().exists()) {
		    		Message.debug("\t"+name+": resource not reachable for "+mrid+": res="+found.getResource());
		    		continue; 
		    	}
	    		break;
	    	}
		}
    	
    	return found;
    }

    /**
     * Output message to log indicating what have been done to look for an artifact which
     * has finally not been found
     * 
     * @param artifact the artifact which has not been found
     */
    protected void logIvyNotFound(ModuleRevisionId mrid) {
        if (isM2compatible()) {
            mrid = convertM2IdForResourceSearch(mrid);
        }
        Artifact artifact = DefaultArtifact.newIvyArtifact(mrid, null);
        logMdNotFound(mrid, artifact);
    }

    protected void logMdNotFound(ModuleRevisionId mrid, Artifact artifact) {
        String revisionToken = mrid.getRevision().startsWith("latest.")?"[any "+mrid.getRevision().substring("latest.".length())+"]":"["+mrid.getRevision()+"]";
        Artifact latestArtifact = new DefaultArtifact(ModuleRevisionId.newInstance(mrid, revisionToken), null, artifact.getName(), artifact.getType(), artifact.getExt(), artifact.getExtraAttributes());
        if (_ivyPatterns.isEmpty()) {
            logIvyAttempt("no ivy pattern => no attempt to find module descriptor file for "+mrid);
        } else {
            for (Iterator iter = _ivyPatterns.iterator(); iter.hasNext();) {
                String pattern = (String)iter.next();
                String resolvedFileName = IvyPatternHelper.substitute(pattern, artifact);
                logIvyAttempt(resolvedFileName);
                if (getSettings().getVersionMatcher().isDynamic(mrid)) {
                    resolvedFileName = IvyPatternHelper.substitute(pattern, latestArtifact);
                    logIvyAttempt(resolvedFileName);
                }
            }
        }
    }

    /**
     * Output message to log indicating what have been done to look for an artifact which
     * has finally not been found
     * 
     * @param artifact the artifact which has not been found
     */
    protected void logArtifactNotFound(Artifact artifact) {
        if (_artifactPatterns.isEmpty()) {
        	if (artifact.getUrl() == null) {
        		logArtifactAttempt(artifact, "no artifact pattern => no attempt to find artifact "+artifact);
        	}
        }
        Artifact used = artifact;
        if (isM2compatible()) {
        	used = DefaultArtifact.cloneWithAnotherMrid(artifact, convertM2IdForResourceSearch(artifact.getModuleRevisionId()));
        }

        for (Iterator iter = _artifactPatterns.iterator(); iter.hasNext();) {
            String pattern = (String)iter.next();
            String resolvedFileName = IvyPatternHelper.substitute(pattern, used);
            logArtifactAttempt(artifact, resolvedFileName);
        }
    	if (used.getUrl() != null) {
    		logArtifactAttempt(artifact, used.getUrl().toString());
    	}
    }

    protected Collection findNames(Map tokenValues, String token) {
        Collection names = new HashSet();
        names.addAll(findIvyNames(tokenValues, token));
        if (isAllownomd()) {
            names.addAll(findArtifactNames(tokenValues, token));
        }
        return names;
    }

    protected Collection findIvyNames(Map tokenValues, String token) {
        Collection names = new HashSet();
        tokenValues = new HashMap(tokenValues);
        tokenValues.put(IvyPatternHelper.ARTIFACT_KEY, "ivy");
        tokenValues.put(IvyPatternHelper.TYPE_KEY, "ivy");
        tokenValues.put(IvyPatternHelper.EXT_KEY, "xml");
        findTokenValues(names, getIvyPatterns(), tokenValues, token);
        getSettings().filterIgnore(names);
        return names;
    }
    
    protected Collection findArtifactNames(Map tokenValues, String token) {
        Collection names = new HashSet();
        tokenValues = new HashMap(tokenValues);
        tokenValues.put(IvyPatternHelper.ARTIFACT_KEY, tokenValues.get(IvyPatternHelper.MODULE_KEY));
        tokenValues.put(IvyPatternHelper.TYPE_KEY, "jar");
        tokenValues.put(IvyPatternHelper.EXT_KEY, "jar");
        findTokenValues(names, getArtifactPatterns(), tokenValues, token);
        getSettings().filterIgnore(names);
        return names;
    }

    // should be overridden by subclasses wanting to have listing features
    protected void findTokenValues(Collection names, List patterns, Map tokenValues, String token) {
    }
    /**
     * example of pattern : ~/Workspace/[module]/[module].ivy.xml
     * @param pattern
     */
    public void addIvyPattern(String pattern) {
        _ivyPatterns.add(pattern);
    }

    public void addArtifactPattern(String pattern) {
        _artifactPatterns.add(pattern);
    }
    
    public List getIvyPatterns() {
        return Collections.unmodifiableList(_ivyPatterns);
    }

    public List getArtifactPatterns() {
        return Collections.unmodifiableList(_artifactPatterns);
    }
    protected void setIvyPatterns(List ivyPatterns) {
        _ivyPatterns = ivyPatterns;
    }
    protected void setArtifactPatterns(List artifactPatterns) {
        _artifactPatterns = artifactPatterns;
    }

    /*
     * Methods respecting ivy conf method specifications
     */
    public void addConfiguredIvy(IvyPattern p) {
        _ivyPatterns.add(p.getPattern());
    }

    public void addConfiguredArtifact(IvyPattern p) {
        _artifactPatterns.add(p.getPattern());
    }
    
    public void dumpSettings() {
        super.dumpSettings();
        Message.debug("\t\tm2compatible: "+isM2compatible());
        Message.debug("\t\tivy patterns:");
        for (ListIterator iter = getIvyPatterns().listIterator(); iter.hasNext();) {
            String p = (String)iter.next();
            Message.debug("\t\t\t"+p);
        }
        Message.debug("\t\tartifact patterns:");
        for (ListIterator iter = getArtifactPatterns().listIterator(); iter.hasNext();) {
            String p = (String)iter.next();
            Message.debug("\t\t\t"+p);
        }
    }

    public boolean isM2compatible() {
        return _m2compatible;
    }

    public void setM2compatible(boolean m2compatible) {
        _m2compatible = m2compatible;
    }

    protected ModuleRevisionId convertM2IdForResourceSearch(ModuleRevisionId mrid) {
        if (mrid.getOrganisation().indexOf('.') == -1) {
            return mrid;
        }
        return ModuleRevisionId.newInstance(mrid.getOrganisation().replace('.', '/'), mrid.getName(), mrid.getBranch(), mrid.getRevision(), mrid.getExtraAttributes());
    }

}