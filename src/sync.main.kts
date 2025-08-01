#!/usr/bin/env kotlin

@file:DependsOn("com.fasterxml.jackson.core:jackson-core:2.16.1")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.16.1")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
@file:DependsOn("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1")

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

data class GiteaConfig(
    val baseUrl: String,
    val token: String,
)

val dryRun = (System.getenv("DRY_RUN") ?: "false") == "true"
val whiteListedOrganizations = (System.getenv("WHITE_LIST_ORGANIZATIONS") ?: "").split(" ").filter { it.isNotEmpty() }.toList()
val blackListedOrganizations = (System.getenv("BLACK_LIST_ORGANIZATIONS") ?: "").split(" ").filter { it.isNotEmpty() }.toList()
val whiteListedRepos = (System.getenv("WHITE_LIST_REPOSITORIES") ?: "").split(" ").filter { it.isNotEmpty() }.toList()
val blackListedRepos = (System.getenv("BLACK_LIST_REPOSITORIES") ?: "").split(" ").filter { it.isNotEmpty() }.toList()

val sourceGitea = GiteaConfig(
    baseUrl = System.getenv("SOURCE_GITEA_URL") ?: throw IllegalArgumentException("SOURCE_GITEA_URL not set"),
    token = System.getenv("SOURCE_GITEA_TOKEN") ?: throw IllegalArgumentException("SOURCE_GITEA_TOKEN not set"),
)
val targetGitea = GiteaConfig(
    baseUrl = System.getenv("TARGET_GITEA_URL") ?: throw IllegalArgumentException("TARGET_GITEA_URL not set"),
    token = System.getenv("TARGET_GITEA_TOKEN") ?: throw IllegalArgumentException("TARGET_GITEA_TOKEN not set"),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Organization(
    val id: Long,
    val name: String,
    val description: String,
    @get:JsonProperty("full_name") val fullName: String,
    val visibility: String,
    val website: String,
    val email: String,
) {
    fun isInSync(other: Organization): Boolean {
        return name == other.name &&
                description == other.description &&
                fullName == other.fullName &&
                visibility == other.visibility &&
                website == other.website &&
                email == other.email
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrganizationCreate(
    val username: String,
    val description: String?,
    @get:JsonProperty("full_name") val fullName: String?,
    val visibility: String = "public",
    val website: String?,
    val email: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrganizationPatch(
    val description: String?,
    @get:JsonProperty("full_name") val fullName: String?,
    val visibility: String,
    val website: String?,
    val email: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Repository(
    val id: Long,
    val name: String,
    val description: String?,
    @get:JsonProperty("full_name") val fullName: String,
    val url: String,
    @get:JsonProperty("default_branch") val defaultBranch: String,
    @get:JsonProperty("html_url") val htmlUrl: String,
    @get:JsonProperty("ssh_url") val sshUrl: String,
    @get:JsonProperty("clone_url") val cloneUrl: String,
    @get:JsonProperty("original_url") val originalUrl: String?,
    val website: String,
    val private: Boolean,
    val mirror: Boolean,
    val archived: Boolean,
    @get:JsonProperty("has_actions") val hasActions: Boolean,
    @get:JsonProperty("has_issues") val hasIssues: Boolean,
    @get:JsonProperty("has_packages") val hasPackages: Boolean,
    @get:JsonProperty("has_projects") val hasProjects: Boolean,
    @get:JsonProperty("has_pull_requests") val hasPullRequests: Boolean,
    @get:JsonProperty("has_releases") val hasReleases: Boolean,
    @get:JsonProperty("has_wiki") val hasWiki: Boolean,
    @get:JsonProperty("mirror_updated") val mirrorUpdated: ZonedDateTime?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryMigrate(
    @get:JsonProperty("repo_owner") val owner: String,
    @get:JsonProperty("repo_name") val name: String,
    val description: String?,
    @get:JsonProperty("clone_addr") val cloneUrl: String,
    @get:JsonProperty("auth_token") val authToken: String,
    @get:JsonProperty("mirror_interval") val mirrorInterval: String = "8h0m0s",
    val private: Boolean,
    val releases: Boolean = true,
    val issues: Boolean = true,
    val wiki: Boolean = true,
) {
    @Suppress("unused")
    val mirror: Boolean = true
    @Suppress("unused")
    val service: String = "gitea"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryPatch(
    val description: String?,
    @get:JsonProperty("default_branch") val defaultBranch: String,
    val website: String,
    val private: Boolean,
    val archived: Boolean,
    @get:JsonProperty("has_issues") val hasIssues: Boolean,
    @get:JsonProperty("has_packages") val hasPackages: Boolean,
    @get:JsonProperty("has_projects") val hasProjects: Boolean,
    @get:JsonProperty("has_pull_requests") val hasPullRequests: Boolean,
    @get:JsonProperty("has_releases") val hasReleases: Boolean,
    @get:JsonProperty("has_wiki") val hasWiki: Boolean,
) {
    @Suppress("unused")
    @get:JsonProperty("has_actions") val hasActions: Boolean = false
    @Suppress("unused")
    @get:JsonProperty("enable_prune") val enablePrune: Boolean = true
}

data class OrganizationRef(
    val server: GiteaConfig,
    val organization: Organization,
)
data class RepositoryRef(
    val server: GiteaConfig,
    val organization: Organization,
    val repository: Repository
)

data class RepoSync(
    val sourceOrganization: OrganizationRef,
    val sourceRepository: RepositoryRef,
    val targetOrganization: OrganizationRef,
    val targetRepository: RepositoryRef?
) {
    fun isIgnored(): Boolean {
        return  targetRepository?.repository?.mirror == false
    }
    fun needsUpdate(): Boolean {
        // mirrors always have hasActions = false, hasPullRequests = false
        return  targetRepository?.repository != null && (
                    sourceRepository.repository.description != targetRepository.repository.description ||
                    sourceRepository.repository.defaultBranch != targetRepository.repository.defaultBranch ||
                    sourceRepository.repository.private != targetRepository.repository.private ||
                    sourceRepository.repository.archived != targetRepository.repository.archived ||
                    sourceRepository.repository.hasIssues != targetRepository.repository.hasIssues ||
                    sourceRepository.repository.hasPackages != targetRepository.repository.hasPackages ||
                    sourceRepository.repository.hasProjects != targetRepository.repository.hasProjects ||
                    sourceRepository.repository.hasReleases != targetRepository.repository.hasReleases ||
                    sourceRepository.repository.hasWiki != targetRepository.repository.hasWiki
                )
    }
    fun needsRecreate(): Boolean {
        return  targetRepository != null && targetRepository.repository.mirror && (
                        sourceRepository.repository.cloneUrl != targetRepository.repository.originalUrl
                )
    }
}

// global tools
val objectMapper = ObjectMapper().registerModules(
    KotlinModule.Builder()
        .configure(KotlinFeature.NullToEmptyCollection, true)
        .configure(KotlinFeature.NullToEmptyMap, true)
        .configure(KotlinFeature.NullIsSameAsDefault, true)
        .configure(KotlinFeature.SingletonSupport, true)
        .configure(KotlinFeature.StrictNullChecks, true)
        .build(),
    JavaTimeModule()
)

data class RequestData(
    val server: GiteaConfig,
    val method: String,
    val path: String,
    val body: Any?
)
fun printRequest(requestData: RequestData) {
    println("> ${requestData.method} ${requestData.server.baseUrl}/api/v1/${requestData.path}")
    println("> Accept: application/json")
    if (requestData.body != null) {
        println("> Content-Type: application/json")
        println("> ")
        println("> ${objectMapper.writeValueAsString(requestData.body)}")
    }
    println("> ")
    println("> ")
}

fun <T> request(requestData: RequestData, consumer: (connection: HttpURLConnection) -> T): T  {
    val url = URL("${requestData.server.baseUrl}/api/v1/${requestData.path}")
    val connection = url.openConnection() as HttpURLConnection
    if (requestData.method == "PATCH") {
        connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        connection.requestMethod = "POST"
    } else {
        connection.requestMethod = requestData.method
    }
    connection.setRequestProperty("Authorization", "token ${requestData.server.token}")
    connection.setRequestProperty("Accept", "application/json")
    if (requestData.body != null) {
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream
            .use {
                objectMapper.writeValue(it, requestData.body)
            }
    }
    return consumer(connection)
}

fun <T> get(server: GiteaConfig, path: String, objectReader: ObjectReader): T?  {
    return request(RequestData(server, "GET", path, null), jsonConsumer(objectReader))
}

fun <T> jsonConsumer(objectReader: ObjectReader): (connection: HttpURLConnection) -> T? {
    return { connection ->
        val responseCode = connection.responseCode
        if (responseCode < 200 || responseCode >= 300)
            null
        else connection.inputStream
            .reader()
            .buffered()
            .use { objectReader.readValue<T>(it) }
    }
}

fun fetchOrganizations(source: GiteaConfig): List<OrganizationRef> {
    return get<List<Organization>>(source, "/orgs", objectMapper.readerForListOf(Organization::class.java))?.map { OrganizationRef(source, it) } ?: emptyList()
}

fun fetchRepositories(source: OrganizationRef): List<RepositoryRef> {
    return get<List<Repository>>(source.server, "/orgs/${source.organization.name}/repos", objectMapper.readerForListOf(Repository::class.java))?.map { RepositoryRef(source.server, source.organization, it) } ?: emptyList()
}

fun createOrganization(target: GiteaConfig, source: OrganizationRef): OrganizationRef? {
    println("creating ${source.organization.name}")
    return RequestData(
            target,
            "POST",
            "/orgs",
            OrganizationCreate(
                username = source.organization.name,
                description = source.organization.description,
                fullName = source.organization.fullName,
                visibility = source.organization.visibility,
                website = source.organization.website,
                email = source.organization.email
            ),
        )
        .let { requestData ->
            if (dryRun) {
                printRequest(requestData)
                null
            } else {
                request(
                    requestData,
                    jsonConsumer<Organization>(objectMapper.readerFor(Organization::class.java))
                )?.let { OrganizationRef(target, it) }
            }
        }
}

fun updateOrganization(target: OrganizationRef, source: OrganizationRef): OrganizationRef? {
    println("updating ${target.organization.name}")
    return RequestData(
            target.server,
            "PATCH",
            "/orgs/${target.organization.name}",
            OrganizationPatch(
                fullName = source.organization.fullName,
                description = source.organization.description,
                email = source.organization.email,
                visibility = source.organization.visibility,
                website = source.organization.website,
            ),
        )
        .let { requestData ->
            if (dryRun) {
                printRequest(requestData)
                null
            } else {
                request(
                    requestData,
                    jsonConsumer<Organization>(objectMapper.readerFor(Organization::class.java))
                )?.let { OrganizationRef(target.server, it) }
            }
        }
}

fun createMirrorRepository(target: OrganizationRef, source: RepositoryRef): RepositoryRef? {
    println("creating ${target.organization.name}/${source.repository.name}")
    return RequestData(
            target.server,
            "POST",
            "/repos/migrate",
            RepositoryMigrate(
                owner = target.organization.name,
                name = source.repository.name,
                description = source.repository.description,
                cloneUrl = source.repository.cloneUrl,
                authToken = source.server.token,
                private = source.repository.private,
                releases = source.repository.hasReleases,
                issues = source.repository.hasIssues,
                wiki = source.repository.hasWiki,
            ),
        )
        .let { requestData ->
            if (dryRun) {
                printRequest(requestData)
                null
            } else {
                request(
                    requestData,
                    jsonConsumer<Repository>(objectMapper.readerFor(Repository::class.java))
                )?.let { RepositoryRef(target.server, target.organization, it) }
            }
        }
}

fun updateRepository(target: RepositoryRef, source: RepositoryRef): RepositoryRef? {
    println("updating ${target.organization.name}/${target.repository.name}")
    return RequestData(
            target.server,
            "PATCH",
            "/repos/${target.organization.name}/${target.repository.name}",
            RepositoryPatch(
                description = source.repository.description,
                defaultBranch = source.repository.defaultBranch,
                website = source.repository.website,
                private = source.repository.private,
                archived = source.repository.archived,
                hasIssues = source.repository.hasIssues,
                hasPackages = source.repository.hasPackages,
                hasProjects = source.repository.hasProjects,
                hasPullRequests = source.repository.hasPullRequests,
                hasReleases = source.repository.hasReleases,
                hasWiki = source.repository.hasWiki,
            ),
        )
        .let { requestData ->
            if (dryRun) {
                printRequest(requestData)
                null
            } else {
                request(
                    requestData,
                    jsonConsumer<Repository>(objectMapper.readerFor(Repository::class.java))
                )?.let { RepositoryRef(target.server, target.organization, it) }
            }
        }
}

fun deleteRepository(target: RepositoryRef): Boolean {
    println("deleting ${target.organization.name}/${target.repository.name}")
    return RequestData(
        target.server,
        "DELETE",
        "/repos/${target.organization.name}/${target.repository.name}",
        null
        )
        .let { requestData ->
            if (dryRun) {
                printRequest(requestData)
                false
            } else {
                request(
                    requestData
                ) { connection ->
                    connection.responseCode == 204
                }
            }
        }
}

fun recreateRepository(target: RepositoryRef, source: RepositoryRef): RepositoryRef? {
    println("have to recreate ${target.organization.name}/${target.repository.name}")
    return if (dryRun)
        target
    else {
        if (deleteRepository(target)) {
            createMirrorRepository(OrganizationRef(target.server, target.organization), source)
        } else {
            target
        }
    }
}

fun <A,B,C> Iterable<Pair<A,B>>.mapValues(lambda: (item: Pair<A, B>) -> C): Iterable<Pair<A,C>> {
    return this.map { it.first to lambda(it) }
}

// Main function
fun main() {
    println("Fetching data from Gitea servers...")
    println("Source: ${sourceGitea.baseUrl}")
    println("Target: ${targetGitea.baseUrl}")
    if (dryRun) {
        println("Dry-Run activated")
    }
    
    val sourceOrgs = fetchOrganizations(sourceGitea)
        .filter { it.organization.name !in blackListedOrganizations }
        .filter { whiteListedOrganizations.isEmpty() || it.organization.name in whiteListedOrganizations }
    val targetOrgs = fetchOrganizations(targetGitea)

    sourceOrgs
        .map { srcOrg ->
            srcOrg to targetOrgs.find { it.organization.name == srcOrg.organization.name }
        }
        .mapValues { (srcOrg, tgtOrg) ->
            if (tgtOrg == null)
                createOrganization(targetGitea, srcOrg)
            else if (!srcOrg.organization.isInSync(tgtOrg.organization))
                updateOrganization( tgtOrg, srcOrg)
            else
                tgtOrg
        }
        .filter { (_, tgtOrg) -> tgtOrg != null }
        .mapValues { (_, tgtOrg) -> tgtOrg!! }
        .flatMap { (srcOrg, tgtOrg) ->
            val srcRepos = fetchRepositories(srcOrg)
                .filter { it.repository.fullName !in blackListedRepos }
                .filter { whiteListedRepos.isEmpty() || it.repository.fullName in whiteListedRepos }
            val tgtRepos = fetchRepositories(tgtOrg)
            srcRepos
                .map { srcRepo ->
                    RepoSync(
                        srcOrg,
                        srcRepo,
                        tgtOrg,
                        tgtRepos.find { it.repository.name == srcRepo.repository.name },
                    )
                }
        }
        .filter { sync -> !sync.sourceRepository.repository.mirror }
        .onEach { sync ->
            if (sync.targetRepository == null)
                createMirrorRepository(sync.targetOrganization, sync.sourceRepository)
            else if (!sync.isIgnored()) {
                if (sync.needsUpdate())
                    updateRepository( sync.targetRepository, sync.sourceRepository)
                else if (sync.needsRecreate())
                    recreateRepository( sync.targetRepository, sync.sourceRepository)
            }
        }

    println("Synchronization completed successfully")
}

main()