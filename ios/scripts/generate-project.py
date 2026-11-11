#!/usr/bin/env python3
"""Regenerate the dependency-free Xcode project; no signing identities are stored."""
import hashlib, json, pathlib, xml.etree.ElementTree as ET
root = pathlib.Path(__file__).resolve().parents[1]
objects = {}
def uid(key): return hashlib.sha256(key.encode()).hexdigest()[:24].upper()
def add(key, isa, **values):
    identifier = uid(key)
    objects[identifier] = {"isa": isa, **values}
    return identifier
files, sources, resources = [], [], []
for name in ["KursfensterApp.swift", "ContentView.swift", "AppModel.swift", "KeychainCredentials.swift", "BookingBackground.swift"]:
    reference = add("file:"+name, "PBXFileReference", lastKnownFileType="sourcecode.swift", path=name, sourceTree="<group>")
    files.append(reference); sources.append(add("build:"+name, "PBXBuildFile", fileRef=reference))
for name, kind in [("Info.plist", "text.plist.xml"), ("Assets.xcassets", "folder.assetcatalog"), ("PrivacyInfo.xcprivacy", "text.xml")]:
    reference = add("file:"+name, "PBXFileReference", lastKnownFileType=kind, path=name, sourceTree="<group>")
    files.append(reference)
    if name != "Info.plist": resources.append(add("build:"+name, "PBXBuildFile", fileRef=reference))
product = add("app-product", "PBXFileReference", explicitFileType="wrapper.application", includeInIndex="0", path="Kursfenster.app", sourceTree="BUILT_PRODUCTS_DIR")
app_group = add("app-group", "PBXGroup", children=files, path="App", sourceTree="<group>")
products_group = add("products-group", "PBXGroup", children=[product], name="Products", sourceTree="<group>")
main_group = add("main-group", "PBXGroup", children=[app_group, products_group], sourceTree="<group>")
package = add("core-package", "XCLocalSwiftPackageReference", relativePath="KursfensterCore")
core_product = add("core-product", "XCSwiftPackageProductDependency", package=package, productName="KursfensterCore")
core_build = add("core-link", "PBXBuildFile", productRef=core_product)
sources_phase = add("sources", "PBXSourcesBuildPhase", buildActionMask="2147483647", files=sources, runOnlyForDeploymentPostprocessing="0")
resources_phase = add("resources", "PBXResourcesBuildPhase", buildActionMask="2147483647", files=resources, runOnlyForDeploymentPostprocessing="0")
frameworks_phase = add("frameworks", "PBXFrameworksBuildPhase", buildActionMask="2147483647", files=[core_build], runOnlyForDeploymentPostprocessing="0")
project_configs, app_configs = [], []
for mode in ["Debug", "Release"]:
    project_configs.append(add("project-config:"+mode, "XCBuildConfiguration", name=mode, buildSettings={
        "CLANG_ENABLE_MODULES": "YES", "SWIFT_VERSION": "5.0", "IPHONEOS_DEPLOYMENT_TARGET": "17.0", "SDKROOT": "iphoneos"}))
    settings = {"PRODUCT_BUNDLE_IDENTIFIER": "de.wiresheep.kursfenster.ios", "PRODUCT_NAME": "$(TARGET_NAME)",
        "INFOPLIST_FILE": "App/Info.plist", "GENERATE_INFOPLIST_FILE": "NO", "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": "3", "MARKETING_VERSION": "0.1.1", "SWIFT_VERSION": "5.0",
        "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if mode == "Debug" else "-O",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG" if mode == "Debug" else "", "SWIFT_EMIT_LOC_STRINGS": "YES",
        "TARGETED_DEVICE_FAMILY": "1,2", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "SUPPORTS_MACCATALYST": "NO",
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon", "LD_RUNPATH_SEARCH_PATHS": ["$(inherited)", "@executable_path/Frameworks"],
        "ENABLE_USER_SCRIPT_SANDBOXING": "YES"}
    app_configs.append(add("app-config:"+mode, "XCBuildConfiguration", name=mode, buildSettings=settings))
project_config_list = add("project-configs", "XCConfigurationList", buildConfigurations=project_configs, defaultConfigurationIsVisible="0", defaultConfigurationName="Release")
app_config_list = add("app-configs", "XCConfigurationList", buildConfigurations=app_configs, defaultConfigurationIsVisible="0", defaultConfigurationName="Release")
app = add("app-target", "PBXNativeTarget", name="Kursfenster", productName="Kursfenster", productReference=product,
    productType="com.apple.product-type.application", buildConfigurationList=app_config_list,
    buildPhases=[sources_phase, frameworks_phase, resources_phase], buildRules=[], dependencies=[], packageProductDependencies=[core_product])
project = add("project", "PBXProject", attributes={"BuildIndependentTargetsInParallel": "YES", "LastUpgradeCheck": "1600",
    "TargetAttributes": {app: {"CreatedOnToolsVersion": "16.0", "SystemCapabilities": {"com.apple.BackgroundModes": {"enabled": "1"}}}}},
    buildConfigurationList=project_config_list, compatibilityVersion="Xcode 15.0", developmentRegion="de", hasScannedForEncodings="0",
    knownRegions=["de", "en", "Base"], mainGroup=main_group, productRefGroup=products_group, projectDirPath="", projectRoot="", targets=[app], packageReferences=[package])
def fmt(value, indent=0):
    if isinstance(value, dict):
        return "{\n"+"".join("\t"*(indent+1)+json.dumps(k)+" = "+fmt(v, indent+1)+";\n" for k,v in value.items())+"\t"*indent+"}"
    if isinstance(value, list): return "(\n"+"".join("\t"*(indent+1)+fmt(v, indent+1)+",\n" for v in value)+"\t"*indent+")"
    return json.dumps(str(value), ensure_ascii=False)
project_dir = root / "Kursfenster.xcodeproj"
project_dir.mkdir(exist_ok=True)
(project_dir / "project.pbxproj").write_text("// !$*UTF8*$!\n"+fmt({"archiveVersion":"1", "classes":{}, "objectVersion":"60", "objects":objects, "rootObject":project})+"\n")
scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{app}" BuildableName="Kursfenster.app" BlueprintName="Kursfenster" ReferencedContainer="container:Kursfenster.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables/></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{app}" BuildableName="Kursfenster.app" BlueprintName="Kursfenster" ReferencedContainer="container:Kursfenster.xcodeproj"/></BuildableProductRunnable></LaunchAction>
 <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{app}" BuildableName="Kursfenster.app" BlueprintName="Kursfenster" ReferencedContainer="container:Kursfenster.xcodeproj"/></BuildableProductRunnable></ProfileAction>
 <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''
scheme_dir = project_dir / "xcshareddata/xcschemes"; scheme_dir.mkdir(parents=True, exist_ok=True)
ET.fromstring(scheme)
(scheme_dir / "Kursfenster.xcscheme").write_text(scheme)
print("Xcode project regenerated:", project_dir.name)
