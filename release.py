import os
import re
from modpack_changelogger import generate_changelog


def extract_version(filename):
    match = re.search(r"(\d+\.\d+\.\d+)", filename)
    if match:
        return tuple(map(int, match.group(1).split(".")))
    return (0, 0, 0)


def update_bug_template(new_version):
    template_file = ".github/ISSUE_TEMPLATE/BUG.yml"

    with open(template_file, "r") as file:
        lines = file.readlines()

    updated_lines = []
    in_options_section = False
    existing_versions = []

    for line in lines:
        if "id: version" in line:
            updated_lines.append(line)
            in_options_section = True
        elif in_options_section and "options:" in line:
            updated_lines.append(line)
            existing_versions = [
                line.strip() for line in lines if line.strip().startswith("-")
            ]
            if f"- {new_version}" not in existing_versions:
                updated_lines.append(f"        - {new_version}\n")
            in_options_section = False
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

    update_bug_template(newest_version)
    print(f"Updated .github/ISSUE_TEMPLATE/BUG.yml with new version: {newest_version}")

else:
    print("Not enough .mrpack files found in the directory to generate a changelog.")
