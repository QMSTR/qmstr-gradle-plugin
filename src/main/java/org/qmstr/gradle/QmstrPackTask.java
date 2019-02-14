package org.qmstr.gradle;

import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.tasks.TaskAction;
import org.qmstr.grpc.service.Datamodel;
import org.qmstr.client.BuildServiceClient;
import org.qmstr.util.FilenodeUtils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class QmstrPackTask extends QmstrTask {

    private ConfigurationContainer config;
    private BuildServiceClient bsc;

    public void setProjectConfig(ConfigurationContainer configurations) {
        this.config = configurations;
    }

    @TaskAction
    void pack() {
        QmstrPluginExtension extension = (QmstrPluginExtension) getProject()
                .getExtensions().findByName("qmstr");

        this.setBuildServiceAddress(extension.qmstrAddress);

        bsc = new BuildServiceClient(buildServiceAddress, buildServicePort);

        HashMap<PublishArtifact, Set<File>> arts = new HashMap<>();
        this.config
                .parallelStream()
                .filter(c -> c.isCanBeResolved())
                .forEach(c -> c.getAllArtifacts().forEach(art -> arts.put(art, c.getResolvedConfiguration().getFiles())));

        bsc.SendBuildFileNodes(arts.entrySet().parallelStream()
                .map(artEntry -> processArtifact(artEntry.getKey(), artEntry.getValue()))
                .filter(o -> o.isPresent())
                .map(o -> o.get())
                .collect(Collectors.toSet())); 
        
    
    }

    public static Optional <Datamodel.FileNode> processArtifact(PublishArtifact artifact, Set<File> dependencySet) {
        if (artifact.getExtension().equals("jar")) {
            try {
                Set<Datamodel.FileNode> classes = new HashSet<>();
                JarFile jar = new JarFile(artifact.getFile());
                jar.stream().parallel()
                        .filter(je -> FilenodeUtils.isSupportedFile(je.getName()))
                        .forEach(je -> {
                            String hash = FilenodeUtils.getHash(jar, je);
                            classes.add(FilenodeUtils.getFileNode(je.getName(), hash, FilenodeUtils.getTypeByFile(je.getName())));
                        });
                Datamodel.FileNode rootNode = FilenodeUtils.getFileNode(artifact.getFile().toPath(), FilenodeUtils.getTypeByFile(artifact.getFile().getName()));
                Datamodel.FileNode.Builder rootNodeBuilder = rootNode.toBuilder();
                classes.forEach(c -> rootNodeBuilder.addDerivedFrom(c));
    
                dependencySet.parallelStream()
                        .map(f -> FilenodeUtils.getFileNode(f.toPath()))
                        .filter(o -> o.isPresent())
                        .map(o -> o.get())
                        .forEach(depNode -> rootNodeBuilder.addDerivedFrom(depNode));
    
                rootNode = rootNodeBuilder.build();
                return Optional.ofNullable(rootNode);
    
            } catch (IOException ioe) {
                //TODO
            }
        }
        return Optional.empty();
    }
}


