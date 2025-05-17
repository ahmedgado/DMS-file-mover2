package entities;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

@Component
@Entity
@Getter @Setter
public class Document implements Serializable , Comparable<Document> {
    @Id
  //  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_seq")
   // @SequenceGenerator(sequenceName = "document_seq", allocationSize = 1, name = "document_seq")
    private Long id;
    @Column
    @Temporal(TemporalType.TIMESTAMP)
    private Date regDate;
    @Column
    private Date docDate;
    @Column
    private String ucmDocID;
    @Column
    private String ucmDocName;
    @Column
    private String fileName;
    @Column
    private String fileType;
    @Column
    private String originalFileName;
    @JoinColumn
    @ManyToOne(fetch = FetchType.EAGER)
    private DocumentType documentType;
    @JoinColumn
    @ManyToOne(fetch = FetchType.EAGER)
    private DocumentMainSubject documentMainSubject;
    @JoinColumn
    @ManyToOne(fetch = FetchType.EAGER)
    private DocumentSubSubject documentSubSubject;
    @JoinColumn
    @ManyToOne
    private Folder folder;
    //New column
    @Column
    private String documentPathInStorage;

    public Document(Long id)
    {
        this.id = id;
    }
    public Document(){}



    @Override
    public int compareTo(Document o) {
        return getRegDate().compareTo(o.getRegDate());
    }

}
