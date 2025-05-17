package entities;


import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.io.Serializable;
import java.util.List;

@Entity
@Getter @Setter
public class DocumentMainSubject implements Serializable {

    public DocumentMainSubject(long id)
    {
        this.id = id;
    }
    public DocumentMainSubject()
    {
        super();
    }
    @Id
    private Long id;
    @Column
    private String name;
    @Column(unique = true)
    private String code;
    @Column
    private Boolean active;
    @ManyToOne
    @JoinColumn
    private DocumentType type;
    @Transient
    private List<DocumentSubSubject> subSubjects;

}
