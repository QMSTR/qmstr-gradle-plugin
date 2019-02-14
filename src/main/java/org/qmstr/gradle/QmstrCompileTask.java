package org.qmstr.gradle;

import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.api.tasks.TaskAction;
import org.qmstr.client.BuildServiceClient;
import org.qmstr.grpc.service.Datamodel;
import org.qmstr.util.FilenodeUtils;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


public class QmstrCompileTask extends QmstrTask {
    private Iterable<SourceSet> sourceSets;
    private BuildServiceClient bsc;

    public void setSourceSets(Iterable<SourceSet> sources) {
        this.sourceSets = sources;
    }

    @TaskAction
    void build() {
        QmstrPluginExtension extension = (QmstrPluginExtension) getProject()
                .getExtensions().findByName("qmstr");

        this.setBuildServiceAddress(extension.qmstrAddress);

        bsc = new BuildServiceClient(buildServiceAddress, buildServicePort);

        this.sourceSets.forEach(set -> {
            FileCollection sourceDirs = set.getAllJava().getSourceDirectories();
            SourceSetOutput outDirs = set.getOutput();
            set.getAllJava().forEach(js -> {
                    Set<Datamodel.FileNode> nodes = processSourceFile(js, sourceDirs, outDirs);
                    if (!nodes.isEmpty()) {
                        bsc.SendBuildFileNodes(nodes);
                    }
            });
        });

    }

    public static Set<Datamodel.FileNode> processSourceFile(File sourcefile, FileCollection sourceDirs, FileCollection outDirs) {

        Datamodel.FileNode sourceNode = FilenodeUtils.getFileNode(sourcefile.toPath(), FilenodeUtils.getTypeByFile(sourcefile.getName()));

        Optional<File> actualSourceDir = sourceDirs
                .filter(sd -> isActualSourceDir(sd, sourcefile))
                .getFiles()
                .stream()
                .findFirst();

        try {
            Path relSrcPath = actualSourceDir.orElseThrow(FileNotFoundException::new).toPath().relativize(sourcefile.toPath());
            String[] filename = relSrcPath.getFileName().toString().split("\\.");
            filename[filename.length-1] = "class";
            String className = String.join(".", filename);
            Path packageDirs = relSrcPath.getParent();
            Path classesRelPath = packageDirs.resolve(className);

            if (packageDirs != null) {
                return outDirs.filter(od -> isActualClassDir(od, classesRelPath)).getFiles().stream()
                        .map(outdir -> {
                            Path classesPath = outdir.toPath().resolve(classesRelPath);
                            Set<Path> nested = getNestedClasses(outdir.toPath().resolve(packageDirs), filename[filename.length - 2]);
                            nested.add(classesPath);
                            return nested.stream()
                                    .map(p -> FilenodeUtils.getFileNode(p, FilenodeUtils.getTypeByFile(p.getFileName().toString())))
                                    .map(node -> node.toBuilder().addDerivedFrom(sourceNode).build())
                                    .collect(Collectors.toSet());
                        }).flatMap(sets -> sets.stream())
                        .collect(Collectors.toSet());
            }
        } catch (FileNotFoundException fnfe) {
            //TODO
        }
        return null;
    }

    private static boolean isActualSourceDir(File sourceDir, File sourceFile) {
        return sourceFile.toString().startsWith(sourceDir.toString());
    }

    private static boolean isActualClassDir(File outdir, Path classesPath) {
        return outdir.toPath().resolve(classesPath).toFile().exists();
    }

    private static Set<Path> getNestedClasses(Path dir, String outerclassname) {
        try {
            return Files.walk(dir)
                    .filter(p -> isNestedClass(p, outerclassname))
                    .collect(Collectors.toSet());

        } catch (IOException e) {
            e.printStackTrace();
            return new HashSet<>();
        }
    }

    private static boolean isNestedClass(Path classesPath, String outerClass) {
        boolean file = classesPath.toFile().isFile();
        String filename = classesPath.getFileName().toString();
        boolean clazz = filename.endsWith(".class");
        boolean starts = filename.startsWith(outerClass + "$");
        return file && clazz && starts;
    }

}