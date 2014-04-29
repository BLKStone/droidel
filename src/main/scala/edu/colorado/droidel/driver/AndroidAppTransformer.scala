package edu.colorado.droidel.driver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.io.File
import java.util.jar.JarFile
import scala.collection.JavaConversions._
import scala.io.Source
import com.ibm.wala.classLoader.IClass
import com.ibm.wala.classLoader.IMethod
import com.ibm.wala.ipa.callgraph.AnalysisScope
import com.ibm.wala.ipa.cha.ClassHierarchy
import com.ibm.wala.ipa.cha.IClassHierarchy
import com.ibm.wala.ssa.SSAInvokeInstruction
import com.ibm.wala.types.ClassLoaderReference
import com.ibm.wala.types.MethodReference
import com.ibm.wala.types.TypeReference
import AndroidAppTransformer._
import edu.colorado.droidel.constants.AndroidConstants
import edu.colorado.droidel.constants.DroidelConstants
import edu.colorado.droidel.constants.AndroidLifecycle
import edu.colorado.droidel.preprocessor.CHAComplementer
import edu.colorado.droidel.parser.LayoutParser
import edu.colorado.droidel.parser.ManifestParser
import edu.colorado.droidel.parser.LayoutElement
import edu.colorado.droidel.parser.LayoutView
import edu.colorado.droidel.parser.LayoutFragment
import edu.colorado.droidel.parser.AndroidManifest
import edu.colorado.droidel.codegen.AndroidHarnessGenerator
import edu.colorado.droidel.codegen.AndroidStubGenerator
import edu.colorado.droidel.instrumenter.BytecodeInstrumenter
import scala.sys.process._
import edu.colorado.droidel.util.CHAUtil
import edu.colorado.droidel.util.ClassUtil
import edu.colorado.droidel.util.IRUtil
import edu.colorado.droidel.util.JavaUtil
import edu.colorado.droidel.util.Timer
import edu.colorado.droidel.util.Util
import com.ibm.wala.types.FieldReference
import com.ibm.wala.ssa.SSANewInstruction
import com.ibm.wala.ssa.SymbolTable

object AndroidAppTransformer {
  val DEBUG = false
}

class AndroidAppTransformer(_appPath : String, androidJar : File, useJPhantom : Boolean = true, cleanupGeneratedFiles : Boolean = true) {
  require(androidJar.exists(), "Couldn't find specified Android JAR file ${androidJar.getAbsolutePath()}")

  val harnessClassName = s"L${DroidelConstants.HARNESS_DIR}${File.separator}${DroidelConstants.HARNESS_CLASS}"
  val harnessMethodName = DroidelConstants.HARNESS_MAIN
  private val appPath = if (_appPath.endsWith(File.separator)) _appPath else s"${_appPath}${File.separator}" 

  private val libJars = {     
    // load libraries in "the libs" directory if they exist
    val libsDir = new File(s"${appPath}${DroidelConstants.LIB_SUFFIX}")
    if (libsDir.exists) libsDir.listFiles().toList.map(f => {
      // TODO: only expecting JAR files here -- be more robust
      assert(f.getName().endsWith(".jar"), s"Unexpected kind of input lib file $f")
      f
    }) else List.empty[File]
  }
  
  private val appBinPath = { // path to the bytecodes for the app
    val unprocessedBinPath = s"${appPath}${DroidelConstants.BIN_SUFFIX}"
    if (useJPhantom) {
      // check for bytecodes that have been processed with JPhantom and use them
      // if they exist. otherwise, created them
      val jPhantomizedBinPath = s"${appPath}${DroidelConstants.JPHANTOMIZED_BIN_SUFFIX}"
      val jPhantomizedBinDir = new File(jPhantomizedBinPath)
      if (jPhantomizedBinDir.exists() && jPhantomizedBinDir.list().length != 0) {
        println("Found JPhantom-processed bytecodes--using them")
        jPhantomizedBinPath
      } else {
        // pre-process the app bytecodes with JPhantom
        val appBinFile = new File(unprocessedBinPath)        
        val originalJarName = "original.jar"
        // create JAR containing original classes
        val originalJar = JavaUtil.createJar(appBinFile, originalJarName, "", startInsideDir = true)                
        val jPhantomTimer = new Timer
        jPhantomTimer.start
        new CHAComplementer(originalJar, androidJar :: libJars, jPhantomizedBinDir).complement
        jPhantomTimer.printTimeTaken("Running JPhantom")
        // remove the JAR we made
        originalJar.delete()
        jPhantomizedBinPath
      }
    } else unprocessedBinPath
  }
  
  private val topLevelAppDir = {
    val f = new File(appPath)
    f.getAbsolutePath().replace(f.getParentFile().getAbsolutePath(), "") match {
      case str if str.startsWith(File.separator) => str.substring(1)
      case str => str
    }
  }
  
  // parse list of Android framework classes / interfaces whose methods are used as callbacks. This list comes from FlowDroid (Arzt et al. PLDI 201414)
  private val callbackClasses = {
    val cbFile = new File(DroidelConstants.CALLBACK_LIST_PATH)
    if (cbFile.exists()) Source.fromFile(cbFile).getLines.foldLeft (Set.empty[TypeReference]) ((set, line) => 
      set + TypeReference.findOrCreate(ClassLoaderReference.Primordial, ClassUtil.walaifyClassName(line))
    ) else sys.error(s"Couldn't find callback list ${DroidelConstants.CALLBACK_LIST_PATH}; exiting")
  }  

  val manifest = new ManifestParser().parseAndroidManifest(new File(appPath))  
  
  // load Android libraries/our stubs addition to the normal analysis scope loading 
  def makeAnalysisScope(useHarness : Boolean = false) : AnalysisScope = {    
    val packagePath = manifest.packageName.replace('.', File.separatorChar)
    val binPath = if (useHarness) s"${appPath}${DroidelConstants.DROIDEL_BIN_SUFFIX}" 
		  else appBinPath
    val applicationCodePath = s"$binPath${File.separator}$packagePath"
    val applicationCodeDir = new File(applicationCodePath)
    assert(applicationCodeDir.exists() && applicationCodeDir.isDirectory(), 
      s"Directory ${applicationCodeDir.getAbsolutePath()} should contain application bytecodes, but does not exist")
      
    val analysisScope = AnalysisScope.createJavaAnalysisScope()

    val harnessFile = if (useHarness) {
      val f = new File(s"${binPath}/${ClassUtil.stripWalaLeadingL(harnessClassName)}.class")
      analysisScope.addClassFileToScope(analysisScope.getApplicationLoader(), f)
      Some(f)
    } else None

   
    // load application code using Application class loader and all library code using Primordial class loader
    // we decide which code is application code using the package path from the application manifest
    val allFiles = Util.getAllFiles(new File(binPath)).filter(f => !f.isDirectory())
    allFiles.foreach(f => assert(!f.getName().endsWith(".jar"), 
                                 s"Not expecting JAR ${f.getAbsolutePath()} in app bin directory"))
    allFiles.foreach(f => if (f.getName().endsWith(".class")) {      
      if (f.getAbsolutePath().contains(applicationCodePath)) analysisScope.addClassFileToScope(analysisScope.getApplicationLoader(), f)
      // ensure the harness class (if any) is only loaded as application; we don't want to reload it as primordial
      else if (!useHarness || f.getAbsolutePath() != harnessFile.get.getAbsolutePath()) analysisScope.addClassFileToScope(analysisScope.getPrimordialLoader(), f)
    })
        
    // if we're using JPhantom, all of the application code and all non-core Java library code (including the Android library)
    // has been deposited into the app bin directory, which has already been loaded. otherwise, we need to load library code
    if (!useJPhantom) {
      // load JAR libraries in libs directory as library code
      libJars.foreach(f => analysisScope.addToScope(analysisScope.getPrimordialLoader(), new JarFile(f)))
      // load Android JAR file as library code
      analysisScope.addToScope(analysisScope.getPrimordialLoader(), new JarFile(androidJar))      
    } 
    
    // load core Java libraries as library code
    // TODO: use or check for Android reimplementation of core libraries?
    getJVMLibFile match { 
      case Some(javaLibJar) => analysisScope.addToScope(analysisScope.getPrimordialLoader(), new JarFile(javaLibJar))
      case None => sys.error("Can't find path to Java libraries. Exiting.")
    }  
    
    analysisScope
  } 

  type LayoutId = Int
  // make mapping from layout ID -> classes that use the corresponding layout
  private def makeLayoutIdToClassMapping(cha : IClassHierarchy) : Map[LayoutId,Set[IClass]] = {
    // TODO: improve these
    val SET_CONTENT_VIEW = "setContentView"
    val INFLATE = "inflate"
    def isSetContentView(m : MethodReference) : Boolean = 
      m.getName().toString() == SET_CONTENT_VIEW 
    def isInflate(m : MethodReference) : Boolean = 
      m.getName().toString() == INFLATE && m.getNumberOfParameters() >= 2 
        
    cha.foldLeft (Map.empty[Int,Set[IClass]]) ((map, c) => 
      if (!ClassUtil.isLibrary(c)) c.getDeclaredMethods.foldLeft (map) ((map, m) => {
        val ir = IRUtil.makeIR(m)
        if (ir != null) {
          val tbl = ir.getSymbolTable()
          ir.iterateNormalInstructions().foldLeft (map) ((map, i) => i match {
            case i : SSAInvokeInstruction if isSetContentView(i.getDeclaredTarget()) || isInflate(i.getDeclaredTarget()) =>
              // TODO: THIS IS A HACK! specify list of inflate methods and parameter nums for layouts
              (0 to i.getNumberOfUses() - 1).find(use => tbl.isIntegerConstant(i.getUse(use))) match {
                case Some(use) =>
                  val viewId = tbl.getIntValue(i.getUse(use))
                  // note that layouts can be reused across multiple classes, and a single class can be associated with multiple layouts
                  Util.updateSMap(map, viewId, c)
                case None => map // TODO: not setting layout using a constant. possibly dynamically created. handle? 
              }
            case _ => map        
          })
        } else {
          if (DEBUG) println(s"Null IR for $m")
          map
        }
      }) else map
    )
  }   
  
   // collect callbacks registered in the manifest
  private def collectManifestDeclaredCallbacks(layoutMap : Map[IClass,Set[LayoutElement]]) : Map[IClass,Set[IMethod]] = {    
    def getEventHandlerMethod(eventHandlerName : String, parentClass : IClass) : IMethod = {
      val eventHandlers = parentClass.getDeclaredMethods().collect({ case m if m.getName().toString() == eventHandlerName => m })
      assert(eventHandlers.size == 1, s"Expected to find exactly one method with name $eventHandlerName; found $eventHandlers")
      eventHandlers.head
    }
    
    layoutMap.foldLeft (Map.empty[IClass,Set[IMethod]]) ((m, entry) => entry._2.foldLeft (m) ((m, v) => v match {
      case v : LayoutView => 
        v.onClick match {
          case Some(onClick) =>
            val callbackClass = entry._1
            // this event handler method was explicitly declared in the manifest (rather than using the default onClick) 
            // look up its parent class, create a MethodReference, and add it to our set of manifest-declared entrypoints
            val callback = getEventHandlerMethod(onClick, callbackClass)
            if (DEBUG) println("Adding manifest-declared entrypoint entrypoint")
            Util.updateSMap(m, callbackClass, callback)
          case None => m // no event handler declared for this layout view
        }
      case v : LayoutFragment =>
        // fragments are odd in that (like View components) they can be created by the frameowrk or the application,
        // but (unlike View components, but like Activity's) they have lifecycle methods
        println(s"Warning: Fragment ${v.typ} detected. We do not currently support the Fragment lifecycle")
        // TODO: add Fragment lifecycle methods here? or support Fragment as a top-level lifecycle type like Activity's?
        //sys.error("unsupported: fragments")
        m
    }))
  }
  
  private def makeSpecializedLayoutTypeCreationMap(stubPaths : Iterable[String]) : Map[TypeReference,MethodReference] = {
    // this is currently hardcoded based on what our stubs currently know what to do: generate specialized findViewById and findFragmentById methods
    val walaViewTypeName = ClassUtil.walaifyClassName(AndroidConstants.VIEW_TYPE)
    val walaFragmentTypeName = ClassUtil.walaifyClassName(AndroidConstants.FRAGMENT_TYPE)
    val viewTypeRef = TypeReference.findOrCreate(ClassLoaderReference.Application, walaViewTypeName)
    val fragmentTypeRef = TypeReference.findOrCreate(ClassLoaderReference.Application, walaFragmentTypeName)
    // TODO: this won't work for multiple files!
    // tell the entrypoint creator to use findViewById() and findFragmentById() to create View's/Fragment's'
    
    stubPaths.foldLeft (Map.empty[TypeReference,MethodReference]) ((m,stubPath) => {
      val viewStubMethod = MethodReference.findOrCreate(ClassLoaderReference.Primordial, 
          ClassUtil.walaifyClassName(stubPath), AndroidConstants.FIND_VIEW_BY_ID, s"(I)$walaViewTypeName")    
      val fragmentStubMethod = MethodReference.findOrCreate(ClassLoaderReference.Primordial, 
          ClassUtil.walaifyClassName(stubPath),  AndroidConstants.FIND_FRAGMENT_BY_ID, s"(I)$walaViewTypeName")
      m + (viewTypeRef -> viewStubMethod, fragmentTypeRef -> fragmentStubMethod)
    })
  }
  
  private def instrumentForApplicationAllocatedCallbackTypes(cha : IClassHierarchy, appCreatedCbMap : Map[IClass,Set[IMethod]], 
      specializedLayoutGettersMap : Map[LayoutId,MethodReference]) : (File, Iterable[FieldReference]) = {
    var dummyID = 0
    def getFreshDummyFieldName : String = { dummyID += 1; s"extracted_$dummyID" }
            
    val harnessClassName = s"L${DroidelConstants.HARNESS_DIR}${File.separator}${DroidelConstants.HARNESS_CLASS}"
    
    val specializedMethodNames = Set( AndroidConstants.FIND_VIEW_BY_ID,  AndroidConstants.FIND_FRAGMENT_BY_ID)
    def isSpecializedMethod(m : MethodReference) : Boolean = specializedMethodNames.contains(m.getName().toString())
    def isSpecializedId(id : LayoutId) : Boolean = specializedLayoutGettersMap.contains(id)
    def isFirstParamSpecializedId(i : SSAInvokeInstruction, tbl : SymbolTable) : Boolean =
      i.getNumberOfUses() > 1 && tbl.isIntegerConstant(i.getUse(1)) && isSpecializedId(tbl.getIntValue(i.getUse(1)))

    
    
    def makeClassName(clazz : IClass) : String = s"${ClassUtil.stripWalaLeadingL(clazz.getName().toString())}.class"   
    // look for application-created callback types by iterating through the class hierarchy instead of the methods in the callgraph.
    // this has pros and cons:
    // pro: iterating over the class hierarchy in a single pass is sound, whereas if we were iterating over the callgraph we would
    // have to iterate, instrument, build harness, rinse and repeat until we reach a fixed point
    // con: if some methods in the class hierarchy aren't reachable, we will extract their application-created callback types anyway.
    // this is sound, but not precise.
    val (instrFlds, allocMap, stubMap) = cha.foldLeft (List.empty[FieldReference], 
                                                       Map.empty[String,Map[IMethod,Iterable[(Int,List[FieldReference])]]],
                                                       Map.empty[String,Map[IMethod,Iterable[(Int,MethodReference)]]]) ((trio, clazz) =>
      if (!ClassUtil.isLibrary(clazz)) {
        val (flds, allocMap, stubMap) = clazz.getDeclaredMethods()
                                        .foldLeft (trio._1, 
                                                   Map.empty[IMethod,List[(Int, List[FieldReference])]],
                                                   Map.empty[IMethod,Iterable[(Int,MethodReference)]]) ((trio, m) => {
          val ir = IRUtil.makeIR(m)
          if (ir != null) {
            val tbl = ir.getSymbolTable()
            val (allocs, calls) = ir.getInstructions().zipWithIndex.foldLeft (List.empty[(Int, List[IClass])],List.empty[(Int,MethodReference)]) ((l, pair) => 
              pair._1 match {
                case i : SSANewInstruction =>
                  cha.lookupClass(i.getConcreteType()) match {
                    case null => l
                    case clazz =>
                      // TODO: use callback finding class here
                      val cbImpls = clazz.getAllImplementedInterfaces().filter(i => callbackClasses.contains(i.getReference())).toList
                      if (cbImpls.isEmpty) l 
                      else {
                        if (DEBUG)
                          println(s"Instrumenting allocation of ${ClassUtil.pretty(i.getConcreteType())} in method ${ClassUtil.pretty(m)} at source line ${IRUtil.getSourceLine(i, ir)}")
                        ((pair._2, cbImpls) :: l._1, l._2)
                      }
                  }                
                case i : SSAInvokeInstruction if isSpecializedMethod(i.getDeclaredTarget()) && isFirstParamSpecializedId(i, tbl) =>
                  if (DEBUG) 
		    println(s"Stubbing out call of ${ClassUtil.pretty(i.getDeclaredTarget())} in method ${ClassUtil.pretty(m)} at source line ${IRUtil.getSourceLine(i, ir)}")
                  (l._1, (pair._2, specializedLayoutGettersMap(tbl.getIntValue(i.getUse(1)))) :: l._2)      
                case _ => l
              }
            )      
            val newCalls = if (calls.isEmpty) trio._3 else trio._3 + (m -> calls)
            val (newFlds, newAllocs) = 
              if (allocs.isEmpty) (trio._1, trio._2) 
              else {
                // create instrumentation vars for each allocation of a callbacky type (one var per callback interface implemented
                val instrumentation = 
                  allocs.map(pair => (pair._1, pair._2.map(c => FieldReference.findOrCreate(ClassLoaderReference.Application,
                                                                                            harnessClassName.toString(),
                                                                                            getFreshDummyFieldName,
                                                                                            c.getName().toString())))
                )
                // update list of instrFields                                                                                                
                val instrFields = instrumentation.foldLeft (trio._1) ((l, pair) => pair._2.foldLeft (l) ((l, f) => f :: l))                                                                                               
                (instrFields, trio._2 + (m -> instrumentation))
              }
            (newFlds, newAllocs, newCalls)
          } else trio
        })
        val className = makeClassName(clazz)
        val newCalls  = if (stubMap.isEmpty) trio._3 else trio._3 + (className -> stubMap)
        val (newFlds, newAllocs) = if (allocMap.isEmpty) (trio._1, trio._2) else (flds, trio._2 + (className -> allocMap))
        (newFlds, newAllocs, newCalls)
      } else trio
    )
    
    // create JAR containing original classes
    val originalJarName = "original.jar"
    val appBinFile = new File(appBinPath)
    val originalJar = JavaUtil.createJar(appBinFile, originalJarName, "", startInsideDir = true)
    
    val cbsToMakePublic = appCreatedCbMap.foldLeft (Map.empty[String,Set[IMethod]]) ((m, entry) => entry._2.filter(m => !m.isPublic()) match {
      case needToMakePublic if needToMakePublic.isEmpty => m
      case needToMakePublic => needToMakePublic.foldLeft (m) ((m, method) => 
        Util.updateSMap(m, makeClassName(method.getDeclaringClass()), method)
      )        
    })
    if (!cbsToMakePublic.isEmpty || !allocMap.isEmpty || !stubMap.isEmpty) { // if there is instrumentation to do      
      val toInstrumentJarName = "toInstrument.jar"
      val instrumentedJarOutputName = "instrumented.jar"
      // create JAR containing classes to instrument only
      val toInstrument = JavaUtil.createJar(appBinFile, toInstrumentJarName, "", startInsideDir = true, j => j.isDirectory() || 
        allocMap.contains(j.getName()) || cbsToMakePublic.contains(j.getName()))
      // perform instrumentation
      val instrumentedJar = new BytecodeInstrumenter().doIt(toInstrument, allocMap, stubMap, cbsToMakePublic, instrumentedJarOutputName)    
      assert(instrumentedJar.exists(), s"Instrumentation did not create JAR file $instrumentedJar")
      
      // merge JAR containing instrumented classes on top of JAR containing original app. very important that instrumented JAR 
      // comes first in the sequence passed to mergeJars, since we want to overwrite some entries in the original JAR
      val mergedJarName = "merged.jar"
      JavaUtil.mergeJars(Seq(instrumentedJar, originalJar), mergedJarName, duplicateWarning = false)
      val newJar = new File(mergedJarName)
      // rename merged JAR to original JAR name
      //newJar.renameTo(originalJar)
      Files.move(newJar.toPath(), originalJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
      toInstrument.delete() // cleanup JAR containing classes to instrument
      instrumentedJar.delete() // cleanup instrumented JAR output
    }
    
    (originalJar, instrFlds)
  }
  
  private def doInstrumentationAndGenerateHarness(cha : IClassHierarchy, manifestDeclaredCallbackMap : Map[IClass,Set[IMethod]], 
      specializedLayoutGettersMap : Map[LayoutId,MethodReference], stubPaths : Iterable[String]) : Unit = {
    println("Performing bytecode instrumentation")
    
    // make a map from framework class -> set of application classes implementing framework class)
    // TODO: use manifest to curate this list. right now we are (soundly, but imprecisely) including too much
    // TODO: curate by reasoning about callback registration. only need to include registered classed
    def makeFrameworkCreatedTypesMap() : Map[IClass,Set[IClass]] = 
      AndroidLifecycle.getFrameworkCreatedClasses(cha).foldLeft (Map.empty[IClass,Set[IClass]]) ((m, c) => 
        cha.computeSubClasses(c.getReference()).filter(c => !ClassUtil.isLibrary(c)) match {
          case appSubclasses if appSubclasses.isEmpty => m
          case appSubclasses =>
            // we only handle public classes because we need to be able to instantiate them and call their callbakcs
            // callback extraction should handle most of the other cases
            // abstract classes cannot be registered for callbacks because they can't be instantiated
            appSubclasses.filter(c => c.isPublic() && !c.isAbstract() && !ClassUtil.isInnerOrEnum(c)) match {
              case concreteSubclasses if concreteSubclasses.isEmpty => m
              case concreteSubclasses => m + (c -> concreteSubclasses.toSet)                
            }
        }
      )
   
    val frameworkCreatedTypesMap = makeFrameworkCreatedTypesMap
                         
    // TODO: parse and check more than just Activity's. also, use the manifest to curate what we include above so we do
    // not include to much
    // sanity check our list of framework created types against the manifest
    val allFrameworkCreatedTypes = frameworkCreatedTypesMap.values.flatten.toSet
    manifest.activities.foreach(a => {        
      val typeRef = TypeReference.findOrCreate(ClassLoaderReference.Application, ClassUtil.walaifyClassName(a.getPackageQualifiedName))
       val clazz = cha.lookupClass(typeRef)
       assert(clazz != null || allFrameworkCreatedTypes.contains(clazz), 
         s"Activity ${a.getPackageQualifiedName} Typeref $typeRef IClass $clazz declared in manifest, but is not in framework-created types map $frameworkCreatedTypesMap")        
    })      
      
    // make a map fron application class -> set of lifecyle and manifest-declared callbacks on application class (+ all on* methods)
    // not that this map does *not* contain callbacks from implementing additional callback interfaces -- these are discovered
    // in the harness generator.
    val frameworkCreatedTypesCallbackMap = frameworkCreatedTypesMap.foldLeft (Map.empty[IClass,Set[IMethod]]) ((m, entry) => {
      val possibleCallbacks = AndroidLifecycle.getCallbacksOnFrameworkCreatedType(entry._1, cha)
      entry._2.foldLeft (m) ((m, appClass) => {
        val appOverrides = {
          possibleCallbacks.filter(method => CHAUtil.mayOverride(method, appClass, cha))
          .map(m => cha.resolveMethod(appClass, m.getSelector())) // TODO: this can fail due to covariance in parameter types/return types. handle
        }          
        // add all methods that start with on for good measure
        // TODO: this is a hack. make an exhaustive list of these methods instead
        val onMethodsAndOverrides = appClass.getAllMethods().foldLeft (appOverrides.toSet) ((s, m) => 
          if (!m.isPrivate() && !ClassUtil.isLibrary(m.getDeclaringClass()) && m.getName().toString().startsWith("on") &&
            // hack to avoid difficulties with generic methods, whose parameter types are often Object
            (0 to m.getNumberOfParameters() - 1).forall(i => m.getParameterType(i).getName() != TypeReference.JavaLangObject.getName())) s + m else s)          
                
          val allCbs = manifestDeclaredCallbackMap.getOrElse(appClass, Set.empty[IMethod]).foldLeft (onMethodsAndOverrides) ((s, m) => s + m)
          assert(!m.contains(appClass), s"Callback map already has entry for app class $appClass")
          m + (appClass ->  allCbs)
        })
      })
      
      // perform two kinds of instrumentations on the bytecode of the app:
      // (1) find all types allocated in the application and instrument the allocating method to extract the allocation via an instrumentation field
      // (2) make all callback methods in the appClassCbMap public so they can be called from the harness 
      val (instrumentedJar, instrumentationFields) = 
        instrumentForApplicationAllocatedCallbackTypes(cha, frameworkCreatedTypesCallbackMap, specializedLayoutGettersMap)
     
      timer.printTimeTaken("Performing bytecode instrumentation")

      println("Generating harness")           
      generateAndroidHarnessAndPackageWithApp(frameworkCreatedTypesCallbackMap, 
                                              manifestDeclaredCallbackMap, instrumentationFields, stubPaths, instrumentedJar, cha)
      timer.printTimeTaken("Generating and compiling harness")

      // no need to keep the JAR; we have an output directory containing these files
      if (instrumentedJar.exists()) instrumentedJar.delete()     
  }
  
  private def generateAndroidHarnessAndPackageWithApp(frameworkCreatedTypesCallbackMap : Map[IClass,Set[IMethod]],
                                                      manifestDeclaredCallbackMap : Map[IClass,Set[IMethod]], 
                                                      instrumentationFields : Iterable[FieldReference],   
                                                      stubPaths : Iterable[String],
                                                      instrumentedJar : File, cha : IClassHierarchy) : File = {  
    
    // create fresh directory for instrumented bytecodes
    val instrumentedBinDirPath = s"${appPath}${DroidelConstants.DROIDEL_BIN_SUFFIX}"
    val instrumentedBinDir = new File(instrumentedBinDirPath)
    if (instrumentedBinDir.exists()) Util.deleteAllFiles(instrumentedBinDir)
    instrumentedBinDir.mkdir()
    
    // extract the JAR containing the instrumented class files
    Process(Seq("jar", "xvf", instrumentedJar.getAbsolutePath()), new File(instrumentedBinDir.getAbsolutePath())).!!

    // note that this automatically moves the compiled harness file into the bin directory for the instrumented app
    val harnessGen = new AndroidHarnessGenerator(cha, instrumentationFields)
    harnessGen.makeSpecializedViewInhabitantCache(stubPaths)
    harnessGen.generateHarness(frameworkCreatedTypesCallbackMap, manifestDeclaredCallbackMap, instrumentedBinDirPath, androidJar.getAbsolutePath())              

    // TODO: just compile them in the right place instead of moving?
    // move stubs in with the apps
    val stubDir = new File(s"$instrumentedBinDir${File.separator}${DroidelConstants.STUB_DIR}")
    if (!stubDir.exists()) stubDir.mkdir()
    stubPaths.foreach(stubPath => {
      val stubFileName = s"$stubPath.class"
      val stubFile = new File(stubFileName)
      assert(stubFile.exists(), s"Can't find stub $stubPath")
      //stubFile.renameTo(new File(s"$instrumentedBinDir${File.separator}$stubFileName"))
      Files.move(stubFile.toPath(), new File(s"$instrumentedBinDir${File.separator}$stubFileName").toPath(), StandardCopyOption.REPLACE_EXISTING)     
    })
    instrumentedBinDir
  }

  def generateStubs(layoutMap : Map[IClass,Set[LayoutElement]], cha : IClassHierarchy) : (List[String], Map[Int,MethodReference]) = {
    println("Generating stubs")
    val res = new AndroidStubGenerator(cha, androidJar.getAbsolutePath()).generateStubs(layoutMap, appBinPath)
    timer.printTimeTaken("Generating and compiling stubs")
    res
  }

  def parseLayout(cha : IClassHierarchy) : (Map[IClass,Set[LayoutElement]], Map[IClass,Set[IMethod]]) = {
    println("Parsing layout")
    val layoutIdClassMap = makeLayoutIdToClassMapping(cha)
    val layoutMap = new LayoutParser().parseAndroidLayout(new File(appPath), new File(appBinPath), manifest, layoutIdClassMap)
    val manifestDeclaredCallbackMap = collectManifestDeclaredCallbacks(layoutMap)
    timer.printTimeTaken("Parsing layout")
    (layoutMap, manifestDeclaredCallbackMap)
  }
  
  val timer = new Timer
  timer.start() 

  def transformApp() : Unit = {
    println(s"Transforming $appPath")
    // create class hierarchy from app
    val analysisScope = makeAnalysisScope()
    val cha = ClassHierarchy.make(analysisScope)
    // parse app layout
    val (layoutMap, manifestDeclaredCallbackMap) = parseLayout(cha)
    // generate app-sepcialized stubs
    val (stubPaths, specializedLayoutGettersMap) = generateStubs(layoutMap, cha)
    val specializedLayoutTypeCreationMap = makeSpecializedLayoutTypeCreationMap(stubPaths)
    // inject the stubs via bytecode instrumentation and generate app-specialized harness
    doInstrumentationAndGenerateHarness(cha, manifestDeclaredCallbackMap, specializedLayoutGettersMap, stubPaths)
    
    // cleanup generated stub and harness source files
    if (cleanupGeneratedFiles) {
      val stubDir = new File(DroidelConstants.STUB_DIR)
      if (stubDir.exists()) Util.deleteAllFiles(stubDir) 
      val harnessDir = new File(DroidelConstants.HARNESS_DIR)
      if (harnessDir.exists()) Util.deleteAllFiles(harnessDir)         
    }    
  }
  
  private def getJVMLibFile : Option[File] = {    
    val PATH = System.getProperty("java.home")
    List(new File(PATH + "/lib/rt.jar"), new File(PATH + "/../Classes/classes.jar")).find(f => f.exists())
  }
    
}
