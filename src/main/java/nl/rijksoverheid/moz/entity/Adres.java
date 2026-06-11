package nl.rijksoverheid.moz.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Embeddable;

@Embeddable
public class Adres {

    private String straat;

    private String huisnummer;

    @Nullable
    private String huisnummerToevoeging;

    private String postcode;

    private String plaats;

    private String land;

    public String getStraat() {
        return straat;
    }

    public void setStraat(String straat) {
        this.straat = straat;
    }

    public String getHuisnummer() {
        return huisnummer;
    }

    public void setHuisnummer(String huisnummer) {
        this.huisnummer = huisnummer;
    }

    @Nullable
    public String getHuisnummerToevoeging() {
        return huisnummerToevoeging;
    }

    public void setHuisnummerToevoeging(@Nullable String huisnummerToevoeging) {
        this.huisnummerToevoeging = huisnummerToevoeging;
    }

    public String getPostcode() {
        return postcode;
    }

    public void setPostcode(String postcode) {
        this.postcode = postcode;
    }

    public String getPlaats() {
        return plaats;
    }

    public void setPlaats(String plaats) {
        this.plaats = plaats;
    }

    public String getLand() {
        return land;
    }

    public void setLand(String land) {
        this.land = land;
    }
}
