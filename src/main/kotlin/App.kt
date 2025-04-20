package dev.samoylenko.client.snyk.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.italic
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.grid
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.YesNoPrompt
import dev.samoylenko.client.snyk.SnykClient
import dev.samoylenko.client.snyk.model.response.ProjectInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAX_TAKE = 5
private const val DELETE_COUNTDOWN = 3 // seconds
private const val PLACEHOLDER_BLANK = "--"

fun main(args: Array<String>) = ClientSnykCli().main(args)

class ClientSnykCli : CliktCommand() {

    private val token by option()
    private val org by argument().optional()
    private val delete by option()
    private val targets by option().flag()

    @OptIn(ExperimentalUuidApi::class)
    override fun run() = runBlocking {

        if (token == null) terminal.println((brightYellow)("Snyk API token was not provided, we will attempt to automatically pick it up from the local configuration"))
        val client = SnykClient(token, 500.milliseconds)
        terminal.print("Fetching Snyk org info... ")
        val orgs = kotlin.runCatching { client.getAllOrgs() }
            .onFailure {
                terminal.println((brightRed)("Error: ${it.message}"))
                terminal.println("It looks like we were not successful working with Snyk API. This is an alpha build of this tool, so we don't know what happened yet, but that simply could be expired OAUTH token.")
                terminal.println((bold)("\nTry running 'snyk container test hello-world', and run this tool again.\n"))
            }
            .getOrThrow()

        terminal.println("Got ${orgs.size} orgs.")

        org?.let { orgParam ->
            val isOrgId = runCatching { Uuid.parse(orgParam) }.isSuccess
            val orgInfo = orgs.firstOrNull {
                if (isOrgId) it.id.contentEquals(orgParam, ignoreCase = true)
                else it.attributes.slug.contentEquals(orgParam, ignoreCase = true)
            } ?: run {
                terminal.println(brightRed("Cannot find org '$orgParam'"))
                throw ProgramResult(1)
            }

            delete?.let { deleteDateString ->
                val deleteDate = LocalDate.parse(deleteDateString)

                terminal.print("Fetching project list... ")
                val projects = client.getOrgProjects(orgInfo.id)
                terminal.println("Got ${projects.size} projects.")
                displayProjects(projects, deleteDate)

                val projectsForDeletion = projects
                    .filter { it.meta?.cliMonitoredAt?.toLocalDateTime(TimeZone.currentSystemDefault())?.date?.let { date -> date <= deleteDate } == true }
                    .associateWith { client.getProjectJiraIssues(orgInfo.id, it.id) }

                if (projectsForDeletion.isNotEmpty()) {
                    terminal.println((brightRed + bold)("WARNING! ALL ITEMS MARKED IN RED WILL BE DELETED! THIS ACTION CANNOT BE REVERTED!"))
                    val response = YesNoPrompt(prompt = "Do you want to proceed?", terminal = terminal).ask()

                    if (response == true) {
                        val jiraIds = mutableSetOf<String>()

                        projectsForDeletion.forEach { (_, jiraTickets) ->
                            val ids = jiraTickets.values.flatMap { jiraIssues -> jiraIssues.map { it.jiraIssue.id } }
                            jiraIds.addAll(ids)
                        }

                        if (jiraIds.isNotEmpty()) {
                            terminal.println((brightYellow + bold)("WARNING: There are ${jiraIds.size} Jira issues associated with the projects on the deletion list!"))
                            terminal.println((brightYellow + italic)("These issues will remain in place and require manual processing."))
                            terminal.println("JQL: id in (${jiraIds.joinToString()})")

                            val response2 =
                                YesNoPrompt(
                                    prompt = "Are absolutely sure you want to proceed?",
                                    terminal = terminal
                                ).ask()
                            if (response2 != true) throw ProgramResult(0)
                        }

                        terminal.print("Deleting projects... ")
                        terminal.println((brightGreen + bold)("(press Control+C to interrupt)"))

                        projectsForDeletion.maxOf { it.key.attributes.name.length }.let { padding ->
                            projectsForDeletion.forEach {
                                terminal.print((yellow)("Deleting ${(brightYellow)(it.key.attributes.name.padEnd(padding))} in "))

                                (DELETE_COUNTDOWN downTo 1).forEach { countdown ->
                                    terminal.print((yellow)("$countdown... "))
                                    delay(1.seconds)
                                }

                                client.deleteProject(orgInfo.id, it.key.id)
                                terminal.println("\uD83D\uDC80") // 💀
                            }
                        }
                    }
                } else {
                    terminal.println((yellow)("There were no projects monitored on or before $deleteDate."))
                }
            } ?: run {
                terminal.println((bold)("Working with org: ${orgInfo.attributes.slug} (${orgInfo.id})\n"))
                terminal.print("Fetching Snyk project info... ")
                val projects = client.getOrgProjects(orgInfo.id)
                terminal.println("Got ${projects.size} projects...")
                terminal.println()
                displayProjects(projects)

                val yesterday = Clock.System.now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date.minus(1, DateTimeUnit.DAY)

                terminal.println("\nUse command line parameter '--delete' to delete projects monitored before a certain date. E.g., '--delete $yesterday'")
            }

            if (targets) cleanupTargets(client, orgInfo.id)

        } ?: run {
            terminal.println("List of all Snyk orgs:\n")

            terminal.println(grid {
                row {
                    style = bold.style
                    cells("ID", "Slug", "Name")
                }

                orgs.forEach { row { cells(it.id, it.attributes.slug, it.attributes.name) } }
            })

            terminal.println((bold)("\nTo select an org to work with, add it's ID or slug to the command line. E.g., '${orgs.first().attributes.slug}'"))
        }
    }

    private fun cleanupTargets(client: SnykClient, orgId: String) = runBlocking {
        terminal.print("\nFetching projects and targets... ")
        val updatedProjects = client.getOrgProjects(orgId)
        terminal.print("Got ${updatedProjects.size} projects. ")
        val targets = client.getOrgTargets(orgId, false)
        terminal.println("And ${targets.size} targets.")

        val targetsByEmpty = targets.groupBy { target ->
            updatedProjects.any { project ->
                project.relationships?.get("target")?.data?.id.contentEquals(target.id, ignoreCase = true)
            }
        }

        targetsByEmpty[false]?.let { emptyTargets ->
            terminal.println("\nEmpty targets detected:\n")
            terminal.println(grid {
                row {
                    style = bold.style
                    cells("Target", "Created", "Private?", "ID")
                }

                emptyTargets.forEach {
                    row {
                        cell(it.attributes.displayName)
                        cell(it.attributes.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date)
                        cell(it.attributes.isPrivate)
                        cell(it.id)
                    }
                }
                row {}
            })


            val response =
                YesNoPrompt(prompt = "Do you want to delete these targets?", terminal = terminal).ask()

            terminal.println()

            if (response == true) emptyTargets.maxOf { it.attributes.displayName.length }.let { padding ->
                emptyTargets.forEach {
                    terminal.print((yellow)("Deleting ${(brightYellow)(it.attributes.displayName.padEnd(padding))}"))
                    client.deleteTarget(orgId, it.id)
                    terminal.println(" \uD83D\uDC80") // 💀
                }

                terminal.println()
            }
        }
    }

    private fun displayProjects(projects: Collection<ProjectInfo>, deletionDate: LocalDate? = null) {

        val projectsByCliMonitoredDate =
            projects.groupBy { it.meta?.cliMonitoredAt?.toLocalDateTime(TimeZone.currentSystemDefault())?.date }

        terminal.println(table {
            tableBorders = Borders.TOP_BOTTOM

            header {
                style = bold.style
                row { cells("Monitored on", "Projects count", "Latest projects") }
            }

            body {
                projectsByCliMonitoredDate
                    .asIterable()
                    .sortedByDescending { (cliMonitoredDate, _) -> cliMonitoredDate }
                    .forEach { (cliMonitoredDate, projects) ->
                        val isDeleted =
                            cliMonitoredDate?.let { m -> deletionDate?.let { d -> if (m > d) false else true } }

                        row {
                            style = isDeleted?.let { d -> if (d) red else green }

                            cell(
                                grid {
                                    if (isDeleted == true) {
                                        row { cell((brightRed + bold)("WILL BE DELETED")) }
                                        row {}
                                    }

                                    row { cell(cliMonitoredDate?.toString() ?: PLACEHOLDER_BLANK) }
                                }
                            )

                            cell(grid {
                                if (isDeleted == true) {
                                    row { cell((brightRed + bold)("WILL BE DELETED")) }
                                    row {}
                                }

                                row { cell(projects.size) }
                            })

                            cell(grid {
                                if (isDeleted == true) {
                                    row { cell((brightRed + bold)("WILL BE DELETED")) }
                                    row {}
                                }

                                projects
                                    .sortedBy { it.meta?.cliMonitoredAt }
                                    .take(MAX_TAKE)
                                    .forEach { row { cell(it.attributes.name) } }

                                if (projects.size > MAX_TAKE) row { cell("... (${projects.size - MAX_TAKE} more) ...") }

                                row {}
                            })
                        }
                    }
            }
        })
    }
}
