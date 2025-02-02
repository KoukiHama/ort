/*
 * Copyright (C) 2022 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.spm

import java.io.File

import kotlinx.serialization.json.decodeFromStream

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.orEmpty
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.utils.common.CommandLineTool
import org.ossreviewtoolkit.utils.common.Os
import org.ossreviewtoolkit.utils.ort.normalizeVcsUrl

private const val PACKAGE_SWIFT_NAME = "Package.swift"
private const val PACKAGE_RESOLVED_NAME = "Package.resolved"

internal const val PACKAGE_TYPE = "SPM"

private const val DEPENDENCIES_SCOPE_NAME = "dependencies"

/**
 * The [Swift Package Manager](https://github.com/apple/swift-package-manager).
 */
class Spm(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    private val graphBuilder = DependencyGraphBuilder(SpmDependencyHandler())

    class Factory : AbstractPackageManagerFactory<Spm>(PACKAGE_TYPE) {
        override val globsForDefinitionFiles = listOf(PACKAGE_SWIFT_NAME, PACKAGE_RESOLVED_NAME)

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Spm(type, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = if (Os.isWindows) "swift.exe" else "swift"

    override fun transformVersion(output: String) = output.substringAfter("version ").substringBefore(" (")

    override fun createPackageManagerResult(projectResults: Map<File, List<ProjectAnalyzerResult>>) =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())

    override fun mapDefinitionFiles(definitionFiles: List<File>): List<File> {
        return definitionFiles.filterNot { file -> file.path.contains(".build/checkouts") }
    }

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> {
        requireLockfile(definitionFile.parentFile) { definitionFile.name != PACKAGE_SWIFT_NAME }

        return when (definitionFile.name) {
            PACKAGE_SWIFT_NAME -> resolveLibraryDependencies(definitionFile)
            else -> resolveAppDependencies(definitionFile)
        }
    }

    /**
     * Resolves dependencies when the final build target is an app and no Package.swift is available.
     * This method parses dependencies from the Package.resolved file.
     */
    private fun resolveAppDependencies(definitionFile: File): List<ProjectAnalyzerResult> {
        val resolved = definitionFile.inputStream().use { json.decodeFromStream<PackageResolved>(it) }

        return listOf(
            ProjectAnalyzerResult(
                project = projectFromDefinitionFile(definitionFile),
                packages = resolved.objects["pins"].orEmpty().mapTo(mutableSetOf()) { it.toPackage() }
            )
        )
    }

    /**
     * Resolves dependencies when the final build target is a library and Package.swift is available.
     * This method parses dependencies from `swift package show-dependencies --format json` output.
     * Also, this method provides parent-child associations for parsed dependencies.
     *
     * Only used when analyzerConfig.allowDynamicVersions is set to true.
     */
    private fun resolveLibraryDependencies(definitionFile: File): List<ProjectAnalyzerResult> {
        val project = projectFromDefinitionFile(definitionFile)

        val result = run(
            definitionFile.parentFile,
            "package",
            "show-dependencies",
            "--format",
            "json"
        ).stdout

        val spmOutput = json.decodeFromString<SpmDependenciesOutput>(result)
        val qualifiedScopeName = DependencyGraph.qualifyScope(scopeName = DEPENDENCIES_SCOPE_NAME, project = project)

        spmOutput.dependencies.onEach { graphBuilder.addDependency(qualifiedScopeName, it) }
            .map { libraryDependency -> libraryDependency.toPackage() }
            .also { graphBuilder.addPackages(it) }

        return listOf(
            ProjectAnalyzerResult(
                project = project.copy(scopeNames = setOf(DEPENDENCIES_SCOPE_NAME)),
                // Packages are set by the dependency handler.
                packages = emptySet(),
                // TODO: Issues might be thrown into stderr. Parse them and add it them to the result as well.
                issues = emptyList()
            )
        )
    }

    private fun projectFromDefinitionFile(definitionFile: File): Project {
        val vcsInfo = VersionControlSystem.forDirectory(definitionFile.parentFile)?.getInfo().orEmpty()
        val (author, project) = parseAuthorAndProjectFromRepo(repositoryURL = vcsInfo.url)

        val projectIdentifier = Identifier(
            type = managerName,
            version = vcsInfo.revision,
            namespace = author.orEmpty(),
            name = project ?: definitionFile.parentFile.relativeTo(analysisRoot).invariantSeparatorsPath
        )

        return Project(
            vcs = VcsInfo.EMPTY,
            id = projectIdentifier,
            authors = emptySet(),
            declaredLicenses = emptySet(),
            homepageUrl = normalizeVcsUrl(vcsInfo.url),
            vcsProcessed = processProjectVcs(definitionFile.parentFile),
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path
        )
    }
}
