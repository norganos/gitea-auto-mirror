#!/usr/bin/env kotlin

@file:DependsOn("com.fasterxml.jackson.core:jackson-core:2.16.1")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.16.1")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
@file:DependsOn("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1")
@file:DependsOn("com.xenomachina:kotlin-argparser:2.0.7")

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.MissingValueException
import com.xenomachina.argparser.SystemExitException
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.util.Scanner

/*
 This grew larger than intended... should we convert this from a kotlin script into a gradle project producing a cli jar?
 Or can we externalize some stuff (don't want to simply create a jar and import it here, as the sources belong together)?
 */


interface OwnerRef {
    val server: GiteaConfig
    val owner: String
}
data class GiteaConfig(
    val token: String,
    val baseUrl: String,
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
data class User(
    val id: Long,
    val login: String,
    val description: String,
    @get:JsonProperty("full_name") val fullName: String,
    val visibility: String,
    val website: String,
    val email: String,
)

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
    override val server: GiteaConfig,
    val organization: Organization,
): OwnerRef {
    override val owner = organization.name
}
data class UserRef(
    override val server: GiteaConfig,
    val user: User,
): OwnerRef {
    override val owner = user.login
}
data class UnknownOwnerRef(
    override val server: GiteaConfig,
    override val owner: String
): OwnerRef
data class RepositoryRef(
    override val server: GiteaConfig,
    override val owner: String,
    val repository: Repository
): OwnerRef

data class RepoSync(
    val sourceOrganization: OwnerRef,
    val sourceRepository: RepositoryRef,
    val targetOrganization: OwnerRef,
    val targetRepository: RepositoryRef?
) {
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
    fun explainUpdate(ctx: CommandContext) {
        if (targetRepository?.repository != null) {
            if (sourceRepository.repository.description != targetRepository.repository.description) {
                ctx.debug(" repos differ in property description: '${sourceRepository.repository.description}' != '${targetRepository.repository.description}'")
            }
            if (sourceRepository.repository.defaultBranch != targetRepository.repository.defaultBranch) {
                ctx.debug(" repos differ in property defaultBranch: '${sourceRepository.repository.defaultBranch}' != '${targetRepository.repository.defaultBranch}'")
            }
            if (sourceRepository.repository.private != targetRepository.repository.private) {
                ctx.debug(" repos differ in property private: '${sourceRepository.repository.private}' != '${targetRepository.repository.private}'")
            }
            if (sourceRepository.repository.archived != targetRepository.repository.archived) {
                ctx.debug(" repos differ in property archived: '${sourceRepository.repository.archived}' != '${targetRepository.repository.archived}'")
            }
            if (sourceRepository.repository.hasIssues != targetRepository.repository.hasIssues) {
                ctx.debug(" repos differ in property hasIssues: '${sourceRepository.repository.hasIssues}' != '${targetRepository.repository.hasIssues}'")
            }
            if (sourceRepository.repository.hasPackages != targetRepository.repository.hasPackages) {
                ctx.debug(" repos differ in property hasPackages: '${sourceRepository.repository.hasPackages}' != '${targetRepository.repository.hasPackages}'")
            }
            if (sourceRepository.repository.hasProjects != targetRepository.repository.hasProjects) {
                ctx.debug(" repos differ in property hasProjects: '${sourceRepository.repository.hasProjects}' != '${targetRepository.repository.hasProjects}'")
            }
            if (sourceRepository.repository.hasReleases != targetRepository.repository.hasReleases) {
                ctx.debug(" repos differ in property hasReleases: '${sourceRepository.repository.hasReleases}' != '${targetRepository.repository.hasReleases}'")
            }
            if (sourceRepository.repository.hasWiki != targetRepository.repository.hasWiki) {
                ctx.debug(" repos differ in property hasWiki: '${sourceRepository.repository.hasWiki}' != '${targetRepository.repository.hasWiki}'")
            }
        }
    }
    fun needsRecreate(): Boolean {
        return  targetRepository != null && targetRepository.repository.mirror && (
                        sourceRepository.repository.cloneUrl != targetRepository.repository.originalUrl
                )
    }
    fun explainRecreate(ctx: CommandContext) {
        if (targetRepository != null && targetRepository.repository.mirror) {
            if (sourceRepository.repository.cloneUrl != targetRepository.repository.originalUrl) {
                ctx.debug(" target repo currently is mirroring ${targetRepository.repository.originalUrl} instead of ${sourceRepository.repository.cloneUrl}")
            }
        }
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

data class ResponseProxy(
    val status: Int,
    val inputStreamProvider: () -> InputStream,
) {
    val inputStream: InputStream by lazy { inputStreamProvider() }
}

fun <T> request(
    ctx: CommandContext,
    server: GiteaConfig,
    method: String,
    path: String,
    body: Any?,
    consumer: (response: ResponseProxy) -> T
): T  {
    val url = URL("${server.baseUrl}/api/v1/${path.trimStart('/')}")
    ctx.printRequest(url, method, body)
    return if (ctx.dryRun && method != "GET") {
        consumer(
            ResponseProxy(
                status = 503,
                inputStreamProvider = { ByteArrayInputStream("".toByteArray()) }
            )
        )
    } else {
        val connection = url.openConnection() as HttpURLConnection
        if (method == "PATCH") { //TODO: java.net does not support PATCH method... convert to apache http-components?
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.requestMethod = "POST"
        } else {
            connection.requestMethod = method
        }
        connection.setRequestProperty("Authorization", "token ${server.token}")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream
                .use {
                    objectMapper.writeValue(it, body)
                }
        }
        consumer(
            ResponseProxy(
                status = connection.responseCode,
                inputStreamProvider = { connection.inputStream }
            )
        )
    }
}

fun <T> get(ctx: CommandContext, server: GiteaConfig, path: String, objectReader: ObjectReader): T?  {
    return request(ctx, server, "GET", path, null, jsonConsumer(objectReader))
}

fun <T> jsonConsumer(objectReader: ObjectReader): (response: ResponseProxy) -> T? {
    return { response ->
        if (response.status < 200 || response.status >= 300)
            null
        else response.inputStream
            .reader()
            .buffered()
            .use { objectReader.readValue<T>(it) }
    }
}

fun fetchOrganizations(ctx: CommandContext, source: GiteaConfig): List<OrganizationRef> {
    return get<List<Organization>>(ctx, source, "/orgs", objectMapper.readerForListOf(Organization::class.java))?.map { OrganizationRef(source, it) } ?: emptyList()
}

fun fetchRepositories(ctx: CommandContext, source: OrganizationRef): List<RepositoryRef> {
    return get<List<Repository>>(ctx, source.server, "/orgs/${source.organization.name}/repos", objectMapper.readerForListOf(Repository::class.java))?.map { RepositoryRef(source.server, source.owner, it) } ?: emptyList()
}

fun fetchUser(ctx: CommandContext, source: GiteaConfig): UserRef? {
    return get<User>(ctx, source, "/user", objectMapper.readerFor(User::class.java))?.let { UserRef(source, it) }
}

fun fetchUserRepositories(ctx: CommandContext, source: UserRef): List<RepositoryRef> {
    return get<List<Repository>>(ctx, source.server, "/user/repos", objectMapper.readerForListOf(Repository::class.java))?.map { RepositoryRef(source.server, source.owner, it) } ?: emptyList()
}

fun createOrganization(ctx: CommandContext, target: GiteaConfig, source: OrganizationRef): OrganizationRef? {
    ctx.info("creating ${source.organization.name}")
    return request(
        ctx,
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
        jsonConsumer<Organization>(objectMapper.readerFor(Organization::class.java))
    )?.let { OrganizationRef(target, it) }
}

fun updateOrganization(ctx: CommandContext, target: OrganizationRef, source: OrganizationRef): OrganizationRef? {
    ctx.info("updating ${target.organization.name}")
    return request(
            ctx,
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
            jsonConsumer<Organization>(objectMapper.readerFor(Organization::class.java))
        )?.let { OrganizationRef(target.server, it) }
}

fun createMirrorRepository(ctx: CommandContext, target: OwnerRef, source: RepositoryRef): RepositoryRef? {
    return request(
            ctx,
            target.server,
            "POST",
            "/repos/migrate",
            RepositoryMigrate(
                owner = target.owner,
                name = source.repository.name,
                description = source.repository.description,
                cloneUrl = source.repository.cloneUrl,
                authToken = source.server.token,
                private = source.repository.private,
                releases = source.repository.hasReleases,
                issues = source.repository.hasIssues,
                wiki = source.repository.hasWiki,
            ),
            jsonConsumer<Repository>(objectMapper.readerFor(Repository::class.java))
        )?.let { RepositoryRef(target.server, target.owner, it) }
}

fun updateRepository(ctx: CommandContext, target: RepositoryRef, source: RepositoryRef): RepositoryRef? {
    return request(
            ctx,
            target.server,
            "PATCH",
            "/repos/${target.owner}/${target.repository.name}",
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
            jsonConsumer<Repository>(objectMapper.readerFor(Repository::class.java))
        )?.let { RepositoryRef(target.server, target.owner, it) }
}

fun deleteRepository(ctx: CommandContext, target: RepositoryRef): Boolean {
    return request(
            ctx,
            target.server,
            "DELETE",
            "/repos/${target.owner}/${target.repository.name}",
            null
        ) { response ->
                response.status == 204
        }
}

fun recreateRepository(ctx: CommandContext, target: RepositoryRef, source: RepositoryRef): RepositoryRef? {
    return if (deleteRepository(ctx, target)) {
        createMirrorRepository(ctx, UnknownOwnerRef(target.server, target.owner), source)
    } else {
        target
    }
}

fun <A,B,C> Iterable<Pair<A,B>>.mapValues(lambda: (item: Pair<A, B>) -> C): Iterable<Pair<A,C>> {
    return this.map { it.first to lambda(it) }
}


data class CommandContext(
    val verbose: Boolean,
    val quiet: Boolean,
    val printRequests: Boolean,
    val dryRun: Boolean,
    val organizations: Boolean,
    val userRepos: Boolean,
    val sourceUrl: String,
    val sourceToken: String,
    val targetUrl: String,
    val targetToken: String,
    val whiteListedOrganizations: List<String>,
    val blackListedOrganizations: List<String>,
    val whiteListedRepos: List<String>,
    val blackListedRepos: List<String>,
) {
    fun info(s: String) {
        if (!quiet) {
            if (printRequests) {
                println("# $s")
            } else {
                println(s)
            }
        }
    }
    fun debug(s: String) {
        if (verbose) {
            if (printRequests) {
                println("# $s")
            } else {
                println(s)
            }
        }
    }

    fun printRequest(url: URL, method: String, body: Any?) {
        if (printRequests) {
            println("$method $url")
            println("Accept: application/json")
            if (body != null) {
                println("Content-Type: application/json")
                println("")
                println(objectMapper.writeValueAsString(body))
            }
            println("")
            println("")
        }
    }
}

@Suppress("CanConvertToMultiDollarString")
class Args(parser: ArgParser) {
    val quiet by parser.flagging("-q", "--quiet", help = "enable quiet mode")
    val verbose by parser.flagging("-v", "--verbose", help = "enable verbose mode")
    val printRequests by parser.flagging("--print-requests", help = "print out all (real and simulated) http requests. if activated, all other output will be preceded by '#'")
    val dryRun by parser.flagging("--dry-run", help = "only report about writing changes, don't actually change anything")

    //TODO: we should offer to reference and load  a config file (containing all the stuff here, source/target, tokens, black-/whitelists)
    val organizations by parser.flagging("-o", "--organizations", help = "sync organizations")
    val userRepos by parser.flagging("-u", "--user-repos", help = "sync repos of accessing user")

    val sourceUrl by parser.storing("-s", "--source-url", help = "URL of source Gitea (default: \$SOURCE_GITEA_URL)").default(System.getenv("SOURCE_GITEA_URL") ?: "")
    val sourceToken by parser.storing("--source-token", help = "Value of source API token (default: \$SOURCE_GITEA_TOKEN). If omitted or empty, it will be read from STDIN. Is is discouraged to pass tokens via this option!").default(System.getenv("SOURCE_GITEA_TOKEN") ?: "")
    val targetUrl by parser.storing("-t", "--target-url", help = "URL of target Gitea (default: \$TARGET_GITEA_URL)").default(System.getenv("TARGET_GITEA_URL") ?: "")
    val targetToken by parser.storing("--target-token", help = "Value of source API token (default: \$TARGET_GITEA_TOKEN). If omitted or empty, it will be read from STDIN. Is is discouraged to pass tokens via this option!").default(System.getenv("TARGET_GITEA_TOKEN") ?: "")

    val whitelistOrganizations by parser.storing("--only-organizations", help = "White-list organizations (separated by comma or space)").default(System.getenv("WHITE_LIST_ORGANIZATIONS") ?: "")
    val blacklistOrganizations by parser.storing("--omit-organizations", help = "Black-list organizations (separated by comma or space)").default(System.getenv("BLACK_LIST_ORGANIZATIONS") ?: "")

    val whitelistRepos by parser.storing("--only-repos", help = "White-list repositories (separated by comma or space, every repo in <owner>/<name> format)").default(System.getenv("WHITE_LIST_REPOSITORIES") ?: "")
    val blacklistRepos by parser.storing("--omit-repos", help = "Black-list repositories (separated by comma or space, every repo in <owner>/<name> format)").default(System.getenv("BLACK_LIST_REPOSITORIES") ?: "")
}

fun syncOrganizations(ctx: CommandContext, sourceGitea: GiteaConfig, targetGitea: GiteaConfig): List<RepoSync> {
    return if (ctx.organizations) {
        val sourceOrgs = fetchOrganizations(ctx, sourceGitea)
            .filter { it.organization.name !in ctx.blackListedOrganizations }
            .filter { ctx.whiteListedOrganizations.isEmpty() || it.organization.name in ctx.whiteListedOrganizations }
        val targetOrgs = fetchOrganizations(ctx, targetGitea)

        sourceOrgs
            .onEach {
                ctx.debug("found organization ${it.organization.name}")
            }
            .map { srcOrg ->
                srcOrg to targetOrgs.find { it.organization.name == srcOrg.organization.name }
            }
            .mapValues { (srcOrg, tgtOrg) ->
                if (ctx.organizations) {
                    if (tgtOrg == null)
                        createOrganization(ctx, targetGitea, srcOrg)
                    else if (!srcOrg.organization.isInSync(tgtOrg.organization))
                        updateOrganization(ctx, tgtOrg, srcOrg)
                    else
                        tgtOrg
                } else tgtOrg
            }
            .filter { (_, tgtOrg) -> tgtOrg != null }
            .mapValues { (_, tgtOrg) -> tgtOrg!! }
            .flatMap { (srcOrg, tgtOrg) ->
                val srcRepos = fetchRepositories(ctx, srcOrg)
                    .filter { it.repository.fullName !in ctx.blackListedRepos }
                    .filter { ctx.whiteListedRepos.isEmpty() || it.repository.fullName in ctx.whiteListedRepos }
                    .onEach {
                        ctx.debug("found repository ${it.repository.fullName}")
                    }
                val tgtRepos = fetchRepositories(ctx, tgtOrg)
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
    } else emptyList()
}

fun syncUserRepos(ctx: CommandContext, sourceGitea: GiteaConfig, targetGitea: GiteaConfig): List<RepoSync> {
    return if (ctx.userRepos) {
        val srcUser = fetchUser(ctx, sourceGitea)
        val tgtUser = fetchUser(ctx, targetGitea)

        ctx.debug("accessing source as ${srcUser?.user?.login}")
        ctx.debug("accessing target as ${tgtUser?.user?.login}")
        if (srcUser != null && tgtUser != null && srcUser.owner == tgtUser.owner) {
            val srcRepos = fetchUserRepositories(ctx, srcUser)
                .filter { it.repository.fullName !in ctx.blackListedRepos }
                .filter { ctx.whiteListedRepos.isEmpty() || it.repository.fullName in ctx.whiteListedRepos }
                .onEach {
                    ctx.debug("found repository ${it.repository.fullName}")
                }
            val tgtRepos = fetchUserRepositories(ctx, tgtUser)
            srcRepos
                .map { srcRepo ->
                    RepoSync(
                        srcUser,
                        srcRepo,
                        tgtUser,
                        tgtRepos.find { it.repository.name == srcRepo.repository.name },
                    )
                }
        } else {
            ctx.debug("skipping user repos as source and target tokens are not from the same user")
            emptyList()
        }
    } else emptyList()
}

fun constructCommandContext(): CommandContext {
    val args = ArgParser(args).parseInto(::Args)

    if (args.sourceUrl.isEmpty()) {
        throw MissingValueException("source-url")
    }
    if (args.targetUrl.isEmpty()) {
        throw MissingValueException("target-url")
    }
    if (!args.userRepos && !args.organizations) {
        throw SystemExitException("neither user nor organization repos specified. Please pass at least one of flags --user-repos or --organizations", 52)
    }
    if (args.verbose && args.quiet) {
        throw SystemExitException("cannot combine --verbose and --quiet", 52)
    }
    var sourceToken = args.sourceToken
    var targetToken = args.targetToken
    if (sourceToken.isEmpty() || targetToken.isEmpty()) {
        System.`in`.reader().use { inputStreamReader ->
            Scanner(inputStreamReader).use { scanner ->
                if (sourceToken.isEmpty()) {
                    println("Source token is not specified. Please enter token for ${args.sourceUrl}:")
                    sourceToken = scanner.nextLine().trim()
                }
                if (targetToken.isEmpty()) {
                    println("Target token is not specified. Please enter token for ${args.targetUrl}:")
                    targetToken = scanner.nextLine().trim()
                }
            }
        }
    }
    if (sourceToken.isEmpty()) {
        throw MissingValueException("source-token")
    }
    if (targetToken.isEmpty()) {
        throw MissingValueException("target-token")
    }
    return CommandContext(
        verbose = args.verbose,
        quiet = args.quiet,
        dryRun = args.dryRun || System.getenv("DRY_RUN") == "true",
        printRequests = args.printRequests,
        organizations = args.organizations,
        userRepos = args.userRepos,
        sourceUrl = args.sourceUrl,
        sourceToken = sourceToken,
        targetUrl = args.targetUrl,
        targetToken = targetToken,
        whiteListedOrganizations = args.whitelistOrganizations.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }.toList(),
        blackListedOrganizations = args.blacklistOrganizations.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }.toList(),
        whiteListedRepos = args.whitelistRepos.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }.toList(),
        blackListedRepos = args.blacklistRepos.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }.toList(),
    )
}

// Main function
fun main() = mainBody {
    val ctx = constructCommandContext()

    val sourceGitea = GiteaConfig(
        baseUrl = ctx.sourceUrl,
        token = ctx.sourceToken
    )
    val targetGitea = GiteaConfig(
        baseUrl = ctx.targetUrl,
        token = ctx.targetToken
    )

    ctx.info("Source: ${sourceGitea.baseUrl}")
    ctx.info("Target: ${targetGitea.baseUrl}")
    if (ctx.dryRun) {
        ctx.info("Dry-Run activated")
    }

    val repos = syncUserRepos(ctx, sourceGitea, targetGitea) + syncOrganizations(ctx, sourceGitea, targetGitea)

    val done = repos
        .filter { sync -> (!sync.sourceRepository.repository.mirror)
            .also {
                if (!it)
                    ctx.info("skipping ${sync.sourceRepository.repository.fullName} because it is a mirror itself")
            }
        }
        .filter { sync -> (sync.targetRepository?.repository?.mirror != false)
            .also {
                if (!it)
                    ctx.info("skipping ${sync.sourceRepository.repository.fullName} because target exists and is not a mirror")
            }
        }
        .onEach { sync ->
            if (sync.targetRepository == null) {
                ctx.info("creating ${sync.targetOrganization.owner}/${sync.sourceRepository.repository.name}")
                createMirrorRepository(ctx, sync.targetOrganization, sync.sourceRepository)
            } else if (sync.needsUpdate()) {
                ctx.info("updating ${sync.targetRepository.owner}/${sync.targetRepository.repository.name}")
                sync.explainUpdate(ctx)
                updateRepository(ctx, sync.targetRepository, sync.sourceRepository)
            } else if (sync.needsRecreate()) {
                ctx.info("have to recreate ${sync.targetRepository.owner}/${sync.targetRepository.repository.name}")
                sync.explainRecreate(ctx)
                recreateRepository(ctx, sync.targetRepository, sync.sourceRepository)
            }
        }
        .count()

    ctx.info("Synchronization completed successfully on $done repositories")
}

main()