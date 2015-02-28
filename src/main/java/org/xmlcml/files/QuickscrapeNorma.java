package org.xmlcml.files;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nu.xom.Element;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;


/** collection of files within the ContentMine system.
 * 
 * The structure of scholarly articles often requires many constituent articles. For example an article may have a PDF, an HTML abstract, several GIFs for images, some tables in HTML, some DOCX files, CIFs for crystallography, etc.. These all need keeping together...

Note that the Catalog (from CottageLabs) primarily holds metadata. [It's possible to hold some of the HTML content, but it soon starts to degrade performance]. We therefore have metadata in the Catalog and contentFiles on disk. These files and Open and can, in principle, be used independently of the Catalog.

I am designing a "QuickscrapeNorma" which passes the bundle down the pipeline. This should be independent of what language [Python , JS, Java...] is used to create or read them. We believe that a normal filing system is satisfactory (at least at present while we develop the technology).

A typical pass for one DOI (e.g. journal.pone.0115884 ) through the pipeline (mandatory files are marked *, optional ?) might look like:

DOI --> Quickscrape -->

create directory  contentmine/some/where/journal.pone.0115884/. It may contain

results.json * // a listing of scraped files

fulltext.xml ? // publishers XML
fulltext.pdf ? // publishers PDF
fulltext.html ? // raw HTML
provisional.pdf ? // provisional PDF (often disappears)

foo12345.docx ? // data files numbered by publisher/author
bar54321.docx ?
ah1234.cif ? // crystallographic data
pqr987.cml ? // chemistry file
mmm.csv ? // table
pic5656.png ? // images
pic5657.gif ? // image
suppdata.pdf ? // supplemental data

and more

only results.json is mandatory. However there will normally be at least one fulltext.* file and probably at least one *.html file (as the landing page must be in HTML). Since quickscrape can extract data without fulltext it might also be deployed against a site with data files.

There may be some redundancy - *.xml may be trasformable into *.html and *.pdf into *.html. The PDF may also contain the same images as some exposed *.png.

==================

This container (directory) is then massed to Norma. Norma will normalize as much information as possible, and we can expect this to continue to develop. This includes:
* conversion to Unicode (XML, HTML, and most "text" files)
* normalization of characters (e.g. Angstrom -> Aring, smart quotes => "", superscript "o" to degrees, etc.)
* creating well-formed HTML (often very hard)
* converting PDF to SVG (very empirical and heuristic)
* converting SVG to running text.
* building primitives (circles, squares, from the raw graphics)
* building graphics objects (arrows, textboxes, flowcharts) from the primitives
* building text from SVG

etc...

This often creates a lot of temporary files, which may be usefully cached for a period. We may create a subdirectory ./svg with intermediate pages, or extracted SVGs. These will be recorded in results.json, which will act as metadata for the files and subdirectories.

Norma will create ./svg/*.svg from PDF (using PDFBox and PDF2SVG), then fulltext.pdf.xhtml (heuristically created XHTML).  Norma will also create wellformed fulltext.html.xhtml from raw fulltext.html or from fulltext.xml.xhtml from fulltext.xml.

In the future Norma will also convert MS-formats such as DOCX and PPT using Apach POI.

Norma will then structure any flat structures into structured XHTML using grouping rules such as in XSLT2.

At this stage we shall have structured XHTML files ("scholarly HTML") with linked images and tables and supplemental data.  We'll update results.json

========================

AMI can further index or transform the ScholarlyHTML and associated files. An AMI plugin (e.g. AMI-species) will produce species.results.xml - a file with the named species in textual context. Similar outputs come from sequence, or other tagging (geotagging).

The main community development will come from regexes. For example we have
regex.crystal.results.xml, regex.farm.results.xml, regex.clinical_trials.results.xml, etc.

The results file include the regexes used and other metadata (more needed!). Again we can update results.json. We may also wish to delete temporary files such as the *.svg in PDF2SVG....

 * 
 * @author pm286
 *
 */
public class QuickscrapeNorma {



	private static final Logger LOG = Logger.getLogger(QuickscrapeNorma.class);
	static {
		LOG.setLevel(Level.DEBUG);
	}

	private static final String DOCX = "docx";
	private static final String EPUB = "epub";
	private static final String HTML = "html";
	private static final String PDF = "pdf";
	private static final String XML = "xml";

	public static final String ABSTRACT_HTML  = "abstract.html";
	public static final String FULLTEXT_DOCX  = "fulltext.docx";
	public static final String FULLTEXT_HTML  = "fulltext.html";
	public static final String FULLTEXT_PDF   = "fulltext.pdf";
	public static final String FULLTEXT_XML   = "fulltext.xml";
	public static final String RESULTS_JSON   = "results.json";
	public static final String RESULTS_XML   = "results.xml";
	public static final String SCHOLARLY_HTML = "scholarly.html";

	public final static List<String> RESERVED_FILE_NAMES;
	static {
			RESERVED_FILE_NAMES = Arrays.asList(new String[] {
					ABSTRACT_HTML,
					FULLTEXT_DOCX,
					FULLTEXT_HTML,
					FULLTEXT_PDF,
					FULLTEXT_XML,
					RESULTS_JSON,
					RESULTS_XML,
					SCHOLARLY_HTML
			});
	}
	public static final String RESULTS_DIR  = "results/";
	public static final String PDF_DIR  = "pdf/";

	public final static List<String> RESERVED_DIR_NAMES;
	static {
			RESERVED_DIR_NAMES = Arrays.asList(new String[] {
					RESULTS_DIR,
					PDF_DIR,
			});
	}
	
	public final static Map<String, String> RESERVED_FILES_BY_EXTENSION = new HashMap<String, String>();
	static {
		RESERVED_FILES_BY_EXTENSION.put(DOCX, FULLTEXT_DOCX);
//		RESERVED_FILES_BY_EXTENSION.put(EPUB, FULLTEXT_EPUB);
		RESERVED_FILES_BY_EXTENSION.put(HTML, FULLTEXT_HTML);
		RESERVED_FILES_BY_EXTENSION.put(PDF, FULLTEXT_PDF);
		RESERVED_FILES_BY_EXTENSION.put(XML, FULLTEXT_XML);
	}
	
	public static boolean isReservedFilename(String name) {
		return RESERVED_FILE_NAMES.contains(name);
	}
	
	public static boolean isReservedDirectory(String name) {
		return RESERVED_DIR_NAMES.contains(name);
	}
	
//	private List<File> fileList;
	private File directory;
	private List<File> reservedFileList;
	private List<File> nonReservedFileList;
	private List<File> reservedDirList;
	private List<File> nonReservedDirList;
	
	public QuickscrapeNorma() {
		
	}
	
	/** creates QN object but does not alter filestore.
	 * 
	 * @param directory
	 */
	public QuickscrapeNorma(File directory) {
		this.directory = directory;
	}
	
	/** ensures filestore matches a QuickscrapeNorma structure.
	 * 
	 * @param directory
	 * @param delete
	 */
	public QuickscrapeNorma(File directory, boolean delete) {
		this(directory);
		this.createDirectory(directory, delete);
	}
	
	public QuickscrapeNorma(String filename) {
		this(new File(filename), false); 
	}

	public void ensureReservedFilenames() {
		if (reservedFileList == null) {
			reservedFileList = new ArrayList<File>();
			nonReservedFileList = new ArrayList<File>();
			reservedDirList = new ArrayList<File>();
			nonReservedDirList = new ArrayList<File>();
			List<File> files = new ArrayList<File>(FileUtils.listFiles(directory, null, false));
			for (File file : files) {
				if (file.isDirectory()) {
					if (isReservedDirectory(FilenameUtils.getName(file.getAbsolutePath()))) {
						reservedDirList.add(file);
					} else {
						nonReservedDirList.add(file);
					}
				} else {
					if (isReservedFilename(FilenameUtils.getName(file.getAbsolutePath()))) {
						reservedFileList.add(file);
					} else {
						nonReservedFileList.add(file);
					}
				}
			}
		}
	}
	
	public List<File> getReservedDirectoryList() {
		ensureReservedFilenames();
		return reservedDirList;
	}
	
	public List<File> getReservedFileList() {
		ensureReservedFilenames();
		return reservedFileList;
	}
	
	public List<File> getNonReservedDirectoryList() {
		ensureReservedFilenames();
		return nonReservedDirList;
	}
	
	public List<File> getNonReservedFileList() {
		ensureReservedFilenames();
		return nonReservedFileList;
	}
	
	public static boolean containsNoReservedFilenames(File dir) {
		if (dir != null && dir.isDirectory()) {
			List<File> files = new ArrayList<File>(FileUtils.listFiles(dir, null, false));
			for (File file : files) {
				String name = FilenameUtils.getName(file.getAbsolutePath());
				if (!isReservedFilename(name)) {
					return false;
				}
			}
		}
		return true;
	}
	
	
	public void createDirectory(File dir, boolean delete) {
		this.directory = dir;
		if (dir == null) {
			throw new RuntimeException("Null directory");
		}
		if (delete) {
			try {
				FileUtils.forceDelete(dir);
			} catch (IOException e) {
				throw new RuntimeException("Cannot delete directory: "+dir, e);
			}
		}
		try {
			FileUtils.forceMkdir(dir);
		} catch (IOException e) {
			throw new RuntimeException("Cannot make directory: "+dir+" already exists");
		} // maybe 
	}

	public void readDirectory(File dir) {
		this.directory = dir;
		Multimap<String, File> map = HashMultimap.create();
		
		requireDirectoryExists(dir);
//		fileList = new ArrayList<File>(FileUtils.listFiles(dir, null, false));
		checkRequiredQuickscrapeFiles();
//		indexByFileExtensions();
	}

//	private void indexByFileExtensions() {
//		for (File file : fileList) {
//			addFileToExtensionTable(file);
//			
//		}
//	}

	private void addFileToExtensionTable(File file) {
		String extension = FilenameUtils.getExtension(file.getName()).toLowerCase();
	}

	private void checkRequiredQuickscrapeFiles() {
		requireExistingNonEmptyFile(new File(directory, RESULTS_JSON));
	}

	// ??
	/*
	private void checkMandatory(String testFilename) {
		testFilename = FilenameUtils.separatorsToUnix(testFilename);
		testFilename = FilenameUtils.normalize(testFilename);
		String testPath = FilenameUtils.getPath(testFilename);
		String testBase = FilenameUtils.getBaseName(testFilename);
		for (File file : fileList) {
			String fname = FilenameUtils.separatorsToUnix(file.getName());
			fname = FilenameUtils.normalize(fname);
			String path = FilenameUtils.getPath(fname);
			String base = FilenameUtils.getBaseName(testFilename);
		}
	}
	*/

//	public List<File> getFileList() {
//		ensureReservedFilenames();
//		return fileList;
//	}

//	public void setFileList(List<File> fileList) {
//		this.fileList = fileList;
//	}

	private static boolean hasExistingFile(File file) {
		boolean ok = (file != null);
		ok &= file.exists();
		ok &= !file.isDirectory();
		return ok;
	}

	private boolean hasExistingSubDirectory(File subdir) {
		return (subdir != null) && subdir.exists() && !subdir.isDirectory();
	}

	private void requireDirectoryExists(File dir) {
		if (dir == null) {
			throw new RuntimeException("Null directory");
		}
		if (!dir.exists()) {
			throw new RuntimeException("Directory: "+dir+" does not exist");
		}
		if (!dir.isDirectory()) {
			throw new RuntimeException("File: "+dir+" is not a directory");
		}
	}
	
	private void requireExistingNonEmptyFile(File file) {
		if (file == null) {
			throw new RuntimeException("Null file");
		}
		if (!file.exists()) {
			throw new RuntimeException("File: "+file+" does not exist");
		}
		if (file.isDirectory()) {
			throw new RuntimeException("File: "+file+" must not be a directory");
		}
		if (FileUtils.sizeOf(file) == 0) {
			throw new RuntimeException("File: "+file+" must not be empty");
		}
	}

	public boolean hasFulltextXML() {
		return hasExistingFile(getExistingFulltextXML());
	}

	public File getExistingFulltextXML() {
		return getExistingReservedFile(FULLTEXT_XML);
	}

	public boolean hasFulltextHTML() {
		return hasExistingFile(new File(directory, FULLTEXT_HTML));
	}
	
	public File getExisitingFulltextHTML() {
		return getExistingReservedFile(FULLTEXT_HTML);
	}

	public boolean hasResultsJSON() {
		return hasExistingFile(new File(directory, RESULTS_JSON));
	}
	
	public File getExistingResultsJSON() {
		return getExistingReservedFile(RESULTS_JSON);
	}

	public boolean hasResultsXML() {
		return hasExistingFile(new File(directory, RESULTS_XML));
	}
	
	public File getExistingResultsXML() {
		return getExistingReservedFile(RESULTS_XML);
	}

	public boolean hasScholarlyHTML() {
		return hasExistingFile(new File(directory, SCHOLARLY_HTML));
	}
	
	public File getExistingScholarlyHTML() {
		return getExistingReservedFile(SCHOLARLY_HTML);
	}

	public boolean hasFulltextPDF() {
		return hasExistingFile(new File(directory, FULLTEXT_PDF));
	}
	
	public File getExisitingFulltextPDF() {
		return getExistingReservedFile(FULLTEXT_PDF);
	}

	public File getReservedFile(String reservedName) {
		
		File file = !isReservedFilename(reservedName) ? null : new File(directory, reservedName);
		return file;
	}

	public File getExistingReservedFile(String reservedName) {
		File file = new File(directory, reservedName);
		return hasExistingFile(file) ? file : null;
	}

	public boolean hasFulltextDOCX() {
		return hasExistingFile(new File(directory, FULLTEXT_DOCX));
	}
	
	public File getFulltextDOCX() {
		return new File(directory, FULLTEXT_DOCX);
	}

	@Override
	public String toString() {
		ensureReservedFilenames();
		StringBuilder sb = new StringBuilder();
		sb.append("dir: "+directory+"\n");
		for (File file : getReservedFileList()) {
			sb.append(file.toString()+"\n");
		}
		return sb.toString();
	}

//	private void ensureFileList() {
//		if (fileList == null) {
//			fileList = new ArrayList<File>();
//		}
//		
//	}

	public void writeFile(String content, String filename) {
		File file = new File(directory, filename);
		if (file.exists()) {
			throw new RuntimeException("file already exists: "+file);
		}
		try {
			FileUtils.write(file, content);
		} catch (IOException e) {
			throw new RuntimeException("Cannot write file: ", e);
		}
	}

	public File getDirectory() {
		return directory;
	}

	public List<File> listFiles(boolean recursive) {
		List<File> files = new ArrayList<File>(FileUtils.listFiles(directory, null, recursive));
		return files;
	}

	public void writeResults(String resultsDirName, String resultsXML) throws Exception {
		File resultsDir = new File(directory, resultsDirName);
		resultsDir.mkdirs();
		File directoryResultsFile = new File(resultsDir, RESULTS_XML);
		FileUtils.writeStringToFile(directoryResultsFile, resultsXML);
	}

	public static String getQNReservedFilenameForExtension(String name) {
		String filename = null;
		String extension = FilenameUtils.getExtension(name);
		if (extension.equals("")) {
			// no type
		} else if (PDF.equals(extension)) {
			filename = FULLTEXT_PDF;
		} else if (XML.equals(extension)) {
			filename = FULLTEXT_XML;
		} else if (HTML.equals(extension)) {
			filename = FULLTEXT_HTML;
		}
		return filename;
	}

	public Element getMetadataElement() {
		Element metadata = new Element("qsNorma");
		metadata.appendChild(this.toString());
		return metadata;
	}

	public static boolean isNonEmptyNonReservedInputList(List<String> inputList) {
		return inputList != null &&
			(inputList.size() != 1 ||
			!QuickscrapeNorma.isReservedFilename(inputList.get(0)));
	}
	
}