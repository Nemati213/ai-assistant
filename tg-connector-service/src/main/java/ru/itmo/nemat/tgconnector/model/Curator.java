package ru.itmo.nemat.tgconnector.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "curators")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Curator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tg_chat_id", nullable = false, unique = true)
    private Long tgChatId;

    @Column(name = "username")
    private String username;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @Column(name = "balance_tokens", nullable = false)
    private BigDecimal balanceTokens;

    @Column(name = "reserved_tokens", nullable = false)
    @Builder.Default
    private BigDecimal reservedTokens = BigDecimal.ZERO;

    @OneToMany(mappedBy = "curator", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<CuratorVkGroup> vkGroups = new ArrayList<>();
}
