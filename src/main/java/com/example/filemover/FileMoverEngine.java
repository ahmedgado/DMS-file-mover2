package com.example.filemover;

import com.example.filemover.repositories.DocumentRepository;
import com.example.filemover.repositories.FolderRepository;
import com.example.filemover.entities.Document;
import com.example.filemover.entities.Folder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Refactored file mover using Producer-Consumer with batching and metadata-based path generation.
 * Includes simple progress logging via System.out.
 */
@Service
public class FileMoverEngine {

    @Value("${file.sourceDir}")
    private String sourceDir;
    @Value("${file.batchSize:1000}")
    private int batchSize;
    @Value("${fs.base.folder}")
    private String fsBaseFolder;

    private final JdbcTemplate jdbc;
    private final BlockingQueue<Path> fileQueue = new LinkedBlockingQueue<>(10000);
    private final BlockingQueue<MoveTask> moveQueue = new LinkedBlockingQueue<>(10000);
    private final int cores = Runtime.getRuntime().availableProcessors();
    private volatile int filesEnqueued = 0;
    private volatile int filesMoved = 0;
    @Autowired
    private DocumentRepository repository;

    @Autowired
    private FolderRepository folderRepo;

    public FileMoverEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void start() {
        System.out.println("Starting FileMoverEngine with " + cores + " cores");
        //createBaseFolderAtFirstToPreventThreads();
        Executors.newSingleThreadExecutor().submit(this::walkFiles);
        ExecutorService lookupPool = Executors.newFixedThreadPool(cores * 2);
        for (int i = 0; i < cores * 2; i++) {
            lookupPool.submit(this::lookupAndEnqueue);
        }
        ExecutorService moverPool = Executors.newFixedThreadPool(cores);
        for (int i = 0; i < cores; i++) {
            moverPool.submit(this::moveFiles);
        }
    }

    private void walkFiles() {
        try {
            System.out.println("Walking directory: " + sourceDir);
            Files.walk(Paths.get(sourceDir))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            fileQueue.put(path);
                            filesEnqueued++;
                            if (filesEnqueued % 1000 == 0) {
                                System.out.println(filesEnqueued + " files enqueued so far");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            System.out.println("Completed enqueueing files. Total: " + filesEnqueued);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void lookupAndEnqueue() {
        List<Path> batch = new ArrayList<>(batchSize);
        while (true) {
            try {
                batch.clear();
                Path first = fileQueue.take();
                batch.add(first);
                fileQueue.drainTo(batch, batchSize - 1);
                System.out.println("Processing batch of " + batch.size() + " files");

                // Extract ucm_docId values from filenames
                List<String> ids = batch.stream()
                        .map(p -> com.google.common.io.Files.getNameWithoutExtension(
                                p.getFileName().toString()))
                        .collect(Collectors.toList());
                String inClause = String.join(",", ids);
                System.out.println("IN clause (ucm_docid IDs): " + inClause);
                if(inClause.isEmpty())
                    return;
                // Query using ucm_docid
                String sql = "SELECT d.ucm_docid AS did , dt.name AS doc_type, ms.name AS main_subject, ss.name AS sub_subject, COALESCE(YEAR(d.doc_date), YEAR(d.reg_date)) AS doc_year " +
                        ", d.document_type_id , document_main_subject_id, document_sub_subject_id " +
                        " FROM dmsapp.document d  LEFT JOIN dmsapp.document_type dt ON d.document_type_id = dt.id  " +
                        "LEFT JOIN dmsapp.document_main_subject ms ON d.document_main_subject_id = ms.id  LEFT JOIN dmsapp.document_sub_subject ss ON d.document_sub_subject_id = ss.id  WHERE d.ucm_docid IN " +
                        "(" + inClause.split("-")[0] + ")";

                List<Map<String, Object>> rows = jdbc.queryForList(sql);

                for (Map<String, Object> row : rows) {
                    String did = ((String) row.get("did"));
                    String docType = sanitize((String) row.get("doc_type"));
                    String mainSubj = sanitize((String) row.get("main_subject"));
                    String subSubj = sanitize((String) row.get("sub_subject"));
                    Long docTypeId = (Long) row.get("document_type_id");
                    Long mainSubjectId= (Long) row.get("document_main_subject_id");
                    Long subSubId=(Long) row.get("document_sub_subject_id");

                    int year = row.get("doc_year") != null ? ((Number) row.get("doc_year")).intValue()
                            : Calendar.getInstance().get(Calendar.YEAR);

                    // Build destination path
                    String destPath = fsBaseFolder + "/" + docType + "/" +
                            mainSubj + "/" + subSubj + "/" + year;
                    System.out.println("&&& "+destPath);
                    List<Folder> folders = createFoldersStructure(destPath,docTypeId ,mainSubjectId
                            , subSubId);
                    Document document = repository.findByUcmDocID(did);
                    Folder documentFolder = folders.get(folders.size() - 1);
                    document.setFolder(documentFolder);
                    document.setDocumentPathInStorage(destPath);


                    // Enqueue move tasks for matching files
                    batch.stream()
                            .filter(p -> com.google.common.io.Files.getNameWithoutExtension(
                                    p.getFileName().toString()).startsWith(String.valueOf(did)))
                            .forEach(p -> {
                                Path target = Paths.get(fsBaseFolder + File.separator + destPath, p.getFileName().toString());
                                try {
                                    String fileOriginalName = p.getFileName().toString().split("-")[1];
                                    DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss");
                                    String fileNameDB =  timeStampPattern.format(java.time.LocalDateTime.now()) + "-" + fileOriginalName;
                                    document.setFileName(fileNameDB);
                                    document.setOriginalFileName(fileOriginalName);
                                    moveQueue.put(new MoveTask(p, target));
                                    repository.save(document);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void moveFiles() {
        while (true) {
            try {
                MoveTask task = moveQueue.take();
                Files.createDirectories(task.dest.getParent());
                Files.move(task.src, task.dest, StandardCopyOption.REPLACE_EXISTING);
                filesMoved++;
                if (filesMoved % 1000 == 0) {
                    System.out.println(filesMoved + " files moved so far");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Sanitize for filesystem */
    private String sanitize(String input) {
        return input == null ? "" : input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public void createBaseFolderAtFirstToPreventThreads()
    {
        Folder parentFolder ;
        parentFolder = new Folder();
        parentFolder.setUcmFullUrl(fsBaseFolder);
        String[] arrayOfParentPath =  PathSeperator.seperate(fsBaseFolder);
        parentFolder.setFolderName(arrayOfParentPath[arrayOfParentPath.length - 1 ]);
        parentFolder = folderRepo.save(parentFolder);
        //parentFolder = folderRepo.save(parentFolder);
        parentFolder.setUcmGUID("FLD_ROOT");
        folderRepo.save(parentFolder);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Folder> createFoldersStructure(String path, Long documentTypeSubjectId, Long documentMainSubjectId, Long documentSubSubjectId) throws Exception  {
        String tempParentGUID = getFolderGUID(fsBaseFolder);
        Folder parentFolder ;
        if(tempParentGUID == null)
        {
            parentFolder = new Folder();
            parentFolder.setUcmFullUrl(fsBaseFolder);
            String[] arrayOfParentPath =  PathSeperator.seperate(fsBaseFolder);
            parentFolder.setFolderName(arrayOfParentPath[arrayOfParentPath.length - 1 ]);
            parentFolder = folderRepo.save(parentFolder);
            tempParentGUID = parentFolder.getId().toString();
            //parentFolder = folderRepo.save(parentFolder);
            parentFolder.setUcmGUID("FLD_ROOT");
            folderRepo.save(parentFolder);

        }
        path = path.replaceAll(fsBaseFolder,"");
        String[] arrayOfPath = PathSeperator.seperate(path);
        String tempPath = "";
        List<Folder> folders = new ArrayList<>();

        for (int i = 0; i < arrayOfPath.length; i++) {
            if (i != 0) tempPath = tempPath + "/" + arrayOfPath[i];
            else tempPath = fsBaseFolder + "/" + arrayOfPath[i];

            ////System.out.println.println("In Step Of Create Folder ::: " + tempPath);


            String folderGUID;
            String parentFolderName;

            folderGUID = getFolderGUID(tempPath);

            if (i == 0) {
                tempParentGUID = getFolderGUID(fsBaseFolder);
                String[] arrayOfParentPath =  PathSeperator.seperate(fsBaseFolder);
                parentFolderName = arrayOfParentPath[arrayOfParentPath.length - 1 ];
                parentFolder = folderRepo.findByUcmGUID(tempParentGUID);
                ////System.out.println.println(tempParentGUID + "Of " + BASE_FOLDER);
            } else {
                parentFolderName = arrayOfPath[i - 1];
                tempParentGUID = getFolderGUIDFromFolderNameAndItsParent(parentFolderName,tempParentGUID);
                parentFolder = folderRepo.findByUcmGUID(tempParentGUID);
            }

            if (folderGUID == null || folderGUID.equals("")) //folder not exist
            {
                Folder folder = new Folder();
                ////System.out.println.println("Not Found " + tempPath);
                try {
                    createFolder(tempParentGUID, arrayOfPath[i],tempPath);
                    //UCM Converter FOR EXPOLORER
                    String[] arrayOfParentPath =  PathSeperator.seperate(fsBaseFolder);
                    String rootName = arrayOfParentPath[arrayOfParentPath.length - 1 ];
                /*    if(parentFolderName.equals(rootName))
                    {
                        folder.setUcmParentFolderGUID("FLD_ROOT");
                        tempParentGUID="FLD_ROOT";
                    }else {
                        folder.setUcmParentFolderGUID(tempParentGUID);
                        tempParentGUID = getFolderGUIDFromFolderNameAndItsParent(parentFolderName,tempParentGUID);

                    }*/
                    folder.setUcmParentFolderGUID(tempParentGUID);
                    folder.setUcmParentFolderName(parentFolderName);
                    folder.setParentFolder(parentFolder);
                    folder.setUcmFullUrl(tempPath);
                    folder.setFolderName(arrayOfPath[i]);
                    if(i==0) {
                        folder.setFolderSubjectType("DOCUMENT_TYPE");
                        folder.setSubjectTypeId(documentTypeSubjectId);
                    }
                    if(i==1) {
                        folder.setFolderSubjectType("DOCUMENT_MAIN_SUB");
                        folder.setSubjectTypeId(documentMainSubjectId);
                    }
                    if(i==2) {
                        folder.setFolderSubjectType("DOCUMENT_SUB_SUBJECT");
                        folder.setSubjectTypeId(documentSubSubjectId);
                    }
                    folder = folderRepo.save(folder);
                    ////System.out.println.println("Folder " + arrayOfPath[i] + "Created" + "under" + "GUID ::" + tempParentGUID);
                    // tempParentGUID = getFolderGUID(tempPath);
                    folder.setUcmGUID(folder.getId().toString());
                    folderRepo.save(folder);
                    folders.add(folder);

                } catch (DataIntegrityViolationException ex) {
                    System.out.println("Skipping duplicate document id={} :" + ex.getMostSpecificCause().getMessage());
                }catch (Exception ex) {
                    ex.printStackTrace();

                }
                ////System.out.println.println("New Folder " + arrayOfPath[i] + "GUID is " + tempParentGUID);

            } else {
                ////System.out.println.println("Folder Found GUID ::: " + folderGUID);
                    /*
                    tempParentGUID = folderGUID;
                    //id -1
                    Folder folder = new Folder();
                    folder.setId(-1l);
                    folder.setUcmGUID(tempParentGUID);
                     */
                folders.add(folderRepo.findByUcmGUID(folderGUID));
            }

        }

        return folders;
    }

    public void createFolder(String parentFolderGUID, String folderName,String folderPath) {
        String parentPath = folderPath.replace(folderName,"");
        if(parentFolderGUID != null) {
            Folder folder = folderRepo.findByUcmGUID(parentFolderGUID);
            if (folder != null && folder.getUcmFullUrl()!= null && !folder.getUcmFullUrl().equals("/")) {
                parentPath = folder.getUcmFullUrl();
            }
        }
        File newDirectory = new File(parentPath, folderName);
        if(!newDirectory.exists())
            newDirectory.mkdirs();
    }
    public String getFolderGUIDFromFolderNameAndItsParent(String folderName,String parentId) throws Exception {
        Optional<Folder> folder = folderRepo.findByFolderNameAndUcmParentFolderGUID(folderName,parentId);
        if(folder.isPresent()){
            return folder.get().getUcmGUID() + "";
        }else {
            //System.out.println.println("folder name not found: " + folderName);
            return null;
        }
    }

    public String getFolderGUID(String folderPath) throws Exception {
        Optional<Folder> folder = folderRepo.findByUcmFullUrl(folderPath);
        if(folder.isPresent()){
            return folder.get().getUcmGUID() + "";
        }else {
            //System.out.println.println("folder path not found: " + folderPath);
            return null;
        }
    }

    private static class MoveTask {
        final Path src;
        final Path dest;
        MoveTask(Path s, Path d) { this.src = s; this.dest = d; }
    }
}
