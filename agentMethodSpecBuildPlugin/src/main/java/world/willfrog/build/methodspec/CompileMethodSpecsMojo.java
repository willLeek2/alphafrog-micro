package world.willfrog.build.methodspec;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Mojo(name = "compile", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresProject = true)
public class CompileMethodSpecsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/finance/method-specs/schema/method-spec-v1.schema.json")
    private String schemaFile;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/finance/method-specs/v1")
    private String specsDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/finance/method-knowledge/v1")
    private String knowledgeDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources")
    private String outputDirectory;

    @Parameter(defaultValue = "true")
    private boolean skipMissingKnowledge;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path schemaPath = Paths.get(schemaFile);
        Path specsDir = Paths.get(specsDirectory);
        Path knowledgeDir = Paths.get(knowledgeDirectory);
        Path outDir = Paths.get(outputDirectory);

        if (!java.nio.file.Files.exists(knowledgeDir)) {
            getLog().info("Knowledge directory does not exist; skipping knowledge combination: " + knowledgeDir);
            knowledgeDir = null;
        }

        try {
            MethodSpecCompiler.CompileResult result = MethodSpecCompiler.compile(
                    schemaPath, specsDir, knowledgeDir, outDir);
            getLog().info("Compiled " + result.specs().size() + " method specs into " + outDir);
            for (Map<String, Object> spec : result.specs()) {
                getLog().info("  " + spec.get("methodId") + "@" + spec.get("version")
                        + " -> " + spec.get("specDigest"));
            }
        } catch (MethodSpecBuildException e) {
            throw new MojoFailureException("MethodSpec compilation failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("MethodSpec compilation I/O error", e);
        }
    }
}
