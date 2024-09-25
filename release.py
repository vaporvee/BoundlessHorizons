import os
import re
from modpack_changelogger import generate_changelog


def extract_version(filename):
    match = re.search(r"(\d+\.\d+\.\d+)", filename)
    if match:
        return tuple(map(int, match.group(1).split(".")))
    return (0, 0, 0)


def update_bug_template(versions):
    template_file = ".github/ISSUE_TEMPLATE/BUG.yml"

    with open(template_file, "r") as file:
        lines = file.readlines()

    updated_lines = []
    in_options_section = False

    for line in lines:
        if "options:" in line:
            in_options_section = True
            updated_lines.append(line)
            for version in versions:
                updated_lines.append(f"        - {version}\n")
        elif in_options_section and line.strip().startswith("-"):
            continue
        else:
            updated_lines.append(line)

    with open(template_file, "w") as file:
        file.writelines(updated_lines)


directory = "mrpack"
changelog_directory = "changelogs"
files = [f for f in os.listdir(directory) if f.endswith(".mrpack")]
print(f"Found mrpack files: {files}")

files_sorted = sorted(files, key=lambda f: extract_version(f), reverse=True)

if len(files_sorted) > 1:
    newest_file = files_sorted[0]
    previous_file = files_sorted[1]
    print(f"Generating changelog for {previous_file} -> {newest_file}")
    newest_version = ".".join(map(str, extract_version(newest_file)))

    changelog_filename = f"changelog_{newest_version}.md"

    generate_changelog(
        f"{directory}/{previous_file}",
        f"{directory}/{newest_file}",
        None,
        f"{changelog_directory}/{changelog_filename}",
    )

    print(f"Changelog generated: {changelog_directory}/{changelog_filename}")

    versions = [".".join(map(str, extract_version(f))) for f in files_sorted]
    update_bug_template(versions)
    print(f"Updated .github/ISSUE_TEMPLATE/BUG.yml with versions: {versions}")

else:
    print("Not enough .mrpack files found in the directory to generate a changelog.")
